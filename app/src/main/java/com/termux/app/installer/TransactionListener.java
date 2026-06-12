package com.termux.app.installer;

import com.termux.shared.errors.Error;

/**
 * Observes an {@link InstallTransaction} as the {@link TransactionCoordinator} drives it through the
 * {@link TransactionState} machine.
 *
 * <p>All methods default to no-ops so a listener only overrides what it needs. The same engine is
 * shared by every flow; each flow supplies its own listener to map state changes to its own
 * presentation (e.g. a progress dialog + retry dialog for bootstrap install, a toast + crash
 * notification for storage symlinks).
 *
 * <p>Callbacks are invoked on the thread that calls {@link TransactionCoordinator#execute}. UI-facing
 * listeners are responsible for marshalling onto the main thread.
 */
public interface TransactionListener {

    /** Invoked whenever the transaction transitions to a different {@link TransactionState}. */
    default void onStateChanged(InstallTransaction transaction, TransactionState previous, TransactionState current) {
    }

    /** Invoked immediately before a step's {@link InstallStep#execute(InstallContext)} is called. */
    default void onStepStarted(InstallTransaction transaction, InstallStep step, int index) {
    }

    /** Invoked when a step fails, before any rollback runs. */
    default void onStepFailed(InstallTransaction transaction, InstallStep step, Error error) {
    }

    /** Invoked once after all steps complete successfully. */
    default void onSucceeded(InstallTransaction transaction) {
    }

    /** Invoked once after a failure (and after any pre-commit rollback has completed). */
    default void onFailed(InstallTransaction transaction, Error error) {
    }
}
