package com.termux.app.installer;

/**
 * The lifecycle states of an {@link InstallTransaction}.
 *
 * <p>The state machine is shared by every install/storage flow (first install, retry, the
 * storage-permission callback and storage symlink creation):
 *
 * <pre>
 *     NOT_STARTED ──▶ RUNNING ──┬──▶ SUCCEEDED
 *                               └──▶ FAILED
 * </pre>
 *
 * The current step index (see {@link InstallTransaction#getCurrentStepIndex()}) identifies the phase
 * within {@link #RUNNING}.
 */
public enum TransactionState {
    /** The transaction has not been executed yet (or has been reset for a new attempt). */
    NOT_STARTED,
    /** The transaction is currently executing its steps. */
    RUNNING,
    /** Every step completed successfully. */
    SUCCEEDED,
    /** A step failed; any pre-commit rollback has already run. */
    FAILED
}
