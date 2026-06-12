package com.termux.app.installer;

import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;

import java.util.List;

/**
 * Runs an {@link InstallTransaction} through the {@link TransactionState} machine, giving every
 * install/storage flow one consistent execution, failure, rollback and retry model.
 *
 * <p>Execution is <em>synchronous</em> on the calling thread, which makes the engine trivially
 * unit-testable; callers that need background execution (e.g. the bootstrap/storage facades) own the
 * worker thread and supply a {@link TransactionListener} that marshals UI work onto the main thread.
 *
 * <p>Semantics:
 * <ul>
 *   <li>Steps run in order. The first step to return a non-null {@link Error} (or to throw) stops
 *       the run; later steps are not executed.</li>
 *   <li>A thrown {@link Throwable} is wrapped into an {@link Error} via
 *       {@link InstallationErrno#ERRNO_STEP_EXECUTION_FAILED} so the failure path is uniform.</li>
 *   <li>If the failure occurred <em>before</em> the transaction's commit boundary, the executed
 *       steps are rolled back in reverse order (best-effort; rollback throwables are swallowed and
 *       logged). A failure at or after the commit boundary is not unwound.</li>
 *   <li>{@link #execute} may be called again on the same transaction to retry; each call begins a
 *       new attempt with reset per-run state.</li>
 * </ul>
 */
public final class TransactionCoordinator {

    private static final String LOG_TAG = "TransactionCoordinator";

    private static final TransactionListener NO_OP_LISTENER = new TransactionListener() {
    };

    /**
     * Execute (or re-execute) the transaction.
     *
     * @return the terminal {@link TransactionState} ({@link TransactionState#SUCCEEDED} or
     * {@link TransactionState#FAILED}).
     */
    public TransactionState execute(InstallTransaction transaction, InstallContext context, TransactionListener listener) {
        final TransactionListener l = (listener != null) ? listener : NO_OP_LISTENER;

        transaction.beginAttempt();
        Logger.logInfo(LOG_TAG, "Running transaction \"" + transaction.getName() + "\" (attempt " + transaction.getAttempt() + ").");
        setState(transaction, TransactionState.RUNNING, l);

        final List<InstallStep> steps = transaction.getSteps();
        Error error = null;
        int lastAttemptedIndex = -1;

        for (int i = 0; i < steps.size(); i++) {
            InstallStep step = steps.get(i);
            transaction.setCurrentStepIndex(i);
            lastAttemptedIndex = i;
            l.onStepStarted(transaction, step, i);
            try {
                error = step.execute(context);
            } catch (Throwable t) {
                error = InstallationErrno.ERRNO_STEP_EXECUTION_FAILED.getError(t, step.getName(), String.valueOf(t.getMessage()));
            }
            if (error != null)
                break;
        }

        if (error == null) {
            transaction.setError(null);
            setState(transaction, TransactionState.SUCCEEDED, l);
            Logger.logInfo(LOG_TAG, "Transaction \"" + transaction.getName() + "\" succeeded.");
            l.onSucceeded(transaction);
            return TransactionState.SUCCEEDED;
        }

        transaction.setError(error);
        final InstallStep failedStep = steps.get(lastAttemptedIndex);
        Logger.logError(LOG_TAG, "Transaction \"" + transaction.getName() + "\" failed at step \""
            + failedStep.getName() + "\": " + error.getMessage());
        l.onStepFailed(transaction, failedStep, error);

        // Only unwind when the failure happened before the commit boundary; once committed, a later
        // failure must not delete the durable result.
        if (lastAttemptedIndex < transaction.getCommitBoundaryIndex()) {
            rollback(transaction, context, lastAttemptedIndex);
        } else {
            Logger.logWarn(LOG_TAG, "Failure at/after commit boundary; not rolling back transaction \""
                + transaction.getName() + "\".");
        }

        setState(transaction, TransactionState.FAILED, l);
        l.onFailed(transaction, error);
        return TransactionState.FAILED;
    }

    private void rollback(InstallTransaction transaction, InstallContext context, int fromIndex) {
        final List<InstallStep> steps = transaction.getSteps();
        for (int i = fromIndex; i >= 0; i--) {
            InstallStep step = steps.get(i);
            try {
                step.rollback(context);
            } catch (Throwable t) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                    "Rollback of step \"" + step.getName() + "\" in transaction \"" + transaction.getName() + "\" failed", t);
            }
        }
    }

    private void setState(InstallTransaction transaction, TransactionState now, TransactionListener listener) {
        TransactionState previous = transaction.getState();
        transaction.setState(now);
        if (previous != now)
            listener.onStateChanged(transaction, previous, now);
    }
}
