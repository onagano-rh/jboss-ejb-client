/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.ejb.client;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.UserTransaction;

/**
 * The transaction context for manual control of transactions on a remote node.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class EJBClientUserTransactionContext extends EJBClientTransactionContext {

    /**
     * User transaction objects are bound to a single source thread; we do not support suspending or resuming
     * transactions in this simple mode.  Asynchronous invocations use the value from the source thread.
     */
    private static final ThreadLocal<State> CURRENT_TRANSACTION_STATE = new ThreadLocal<State>();

    /** {@inheritDoc} */
    protected UserTransactionID associate(final EJBClientInvocationContext<?> invocationContext) {
        final State state = CURRENT_TRANSACTION_STATE.get();
        return state == null ? null : state.currentId;
    }

    /**
     * Get a {@link UserTransaction} instance affiliated with a specific remote node to control the transaction
     * state.  The instance is only usable while there is an active connection with the given peer.
     *
     * @param nodeName the remote node name
     * @return the user transaction instance
     */
    public UserTransaction getUserTransaction(String nodeName) {
        return new UserTransactionImpl(nodeName);
    }

    private static final AtomicInteger idCounter = new AtomicInteger(new Random().nextInt());

    static class State {
        UserTransactionID currentId;
        int status = Status.STATUS_NO_TRANSACTION;
        int timeout = 0;

        State() {
        }
    }

    class UserTransactionImpl implements UserTransaction {
        private final String nodeName;

        UserTransactionImpl(final String nodeName) {
            this.nodeName = nodeName;
        }

        public void begin() throws NotSupportedException, SystemException {
            State state = CURRENT_TRANSACTION_STATE.get();
            if (state == null) {
                CURRENT_TRANSACTION_STATE.set(state = new State());
            }
            if (state.currentId != null) {
                throw new NotSupportedException("A transaction is already associated with this thread");
            }
            boolean ok = false;
            final UserTransactionID transactionID = new UserTransactionID(nodeName, idCounter.getAndAdd(127));
            final int timeout = state.timeout;
            try {
                final EJBClientContext clientContext = EJBClientContext.requireCurrent();
                final EJBReceiver<?> receiver = clientContext.getNodeEJBReceiver(nodeName);
                // receiver.sendBegin(transactionID, timeout);
            } finally {
                if (ok) {
                    state.currentId = transactionID;
                    state.status = Status.STATUS_ACTIVE;
                }
            }
        }

        public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {
            final State state = CURRENT_TRANSACTION_STATE.get();
            if (state == null || state.currentId == null) {
                throw new IllegalStateException("A transaction is not associated with this thread");
            }
            if (state.status != Status.STATUS_ACTIVE && state.status != Status.STATUS_MARKED_ROLLBACK) {
                throw new IllegalStateException("Transaction for this thread is not active");
            }
            final UserTransactionID transactionID = new UserTransactionID(nodeName, idCounter.getAndAdd(127));
            try {
                final EJBClientContext clientContext = EJBClientContext.requireCurrent();
                final EJBReceiver<?> receiver = clientContext.getNodeEJBReceiver(nodeName);
                if (state.status == Status.STATUS_MARKED_ROLLBACK) {
                    state.status = Status.STATUS_ROLLING_BACK;
                    try {
                        // receiver.sendRollback(transactionID);
                    } catch (Throwable ignored) {
                        // log it maybe?
                    }
                    throw new RollbackException("Transaction marked for rollback only");
                } else {
                    state.status = Status.STATUS_COMMITTING;
                    // receiver.sendCommit(transactionID);
                }
            } finally {
                state.currentId = null;
                state.status = Status.STATUS_NO_TRANSACTION;
            }
        }

        public void rollback() throws IllegalStateException, SecurityException, SystemException {
            final State state = CURRENT_TRANSACTION_STATE.get();
            if (state == null || state.currentId == null) {
                throw new IllegalStateException("A transaction is not associated with this thread");
            }
            if (state.status != Status.STATUS_ACTIVE && state.status != Status.STATUS_MARKED_ROLLBACK) {
                throw new IllegalStateException("Transaction for this thread is not active");
            }
            final UserTransactionID transactionID = new UserTransactionID(nodeName, idCounter.getAndAdd(127));
            try {
                final EJBClientContext clientContext = EJBClientContext.requireCurrent();
                final EJBReceiver<?> receiver = clientContext.getNodeEJBReceiver(nodeName);
                state.status = Status.STATUS_ROLLING_BACK;
                // receiver.sendRollback(transactionID);
            } finally {
                state.currentId = null;
                state.status = Status.STATUS_NO_TRANSACTION;
            }
        }

        public void setRollbackOnly() throws IllegalStateException, SystemException {
            final State state = CURRENT_TRANSACTION_STATE.get();
            if (state != null) switch (state.status) {
                case Status.STATUS_ROLLING_BACK:
                case Status.STATUS_ROLLEDBACK:
                case Status.STATUS_MARKED_ROLLBACK: {
                    // nothing to do
                    return;
                }
                case Status.STATUS_ACTIVE: {
                    // mark rollback
                    state.status = Status.STATUS_MARKED_ROLLBACK;
                    return;
                }
            }
            throw new IllegalStateException("Transaction not active");
        }

        public int getStatus() throws SystemException {
            final State state = CURRENT_TRANSACTION_STATE.get();
            return state == null ? Status.STATUS_NO_TRANSACTION : state.status;
        }

        public void setTransactionTimeout(int seconds) throws SystemException {
            if (seconds < 0) {
                seconds = 0;
            }
            State state = CURRENT_TRANSACTION_STATE.get();
            if (state == null) {
                CURRENT_TRANSACTION_STATE.set(state = new State());
            }
            state.timeout = seconds;
        }
    }
}
