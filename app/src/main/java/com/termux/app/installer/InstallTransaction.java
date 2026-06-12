package com.termux.app.installer;

import com.termux.shared.errors.Error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An ordered sequence of {@link InstallStep}s executed as a unit by the {@link TransactionCoordinator},
 * together with the mutable state of the current run.
 *
 * <p>The {@code commitBoundaryIndex} marks the first step that is considered <em>post-commit</em>:
 * a failure at or after that index does not trigger rollback (the work up to the commit is already
 * durable and must not be unwound). For a transaction with no commit semantics, the boundary equals
 * the step count, so every executed step is eligible for rollback.
 *
 * <p>This type is not thread-safe; a transaction instance is intended to be run by a single
 * coordinator at a time. Re-running (retry) is supported via {@link #beginAttempt()}, which the
 * coordinator calls at the start of each run.
 */
public final class InstallTransaction {

    private final String name;
    private final List<InstallStep> steps;
    private final int commitBoundaryIndex;

    private TransactionState state = TransactionState.NOT_STARTED;
    private int currentStepIndex = -1;
    private Error error;
    private int attempt = 0;

    /** Creates a transaction with no commit boundary (every executed step may be rolled back). */
    public InstallTransaction(String name, List<InstallStep> steps) {
        this(name, steps, steps.size());
    }

    public InstallTransaction(String name, List<InstallStep> steps, int commitBoundaryIndex) {
        this.name = name;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        this.commitBoundaryIndex = commitBoundaryIndex;
    }

    public String getName() {
        return name;
    }

    public List<InstallStep> getSteps() {
        return steps;
    }

    /** Index of the first post-commit step. Failures at or after this index are not rolled back. */
    public int getCommitBoundaryIndex() {
        return commitBoundaryIndex;
    }

    public TransactionState getState() {
        return state;
    }

    /** Index of the step currently running or last attempted; {@code -1} before the first step. */
    public int getCurrentStepIndex() {
        return currentStepIndex;
    }

    /** The failure of the last run, or {@code null} if it has not failed. */
    public Error getError() {
        return error;
    }

    /** The number of times this transaction has been run (incremented by {@link #beginAttempt()}). */
    public int getAttempt() {
        return attempt;
    }

    // --- Mutators used by TransactionCoordinator (package-private) ---

    /** Resets per-run state and increments the attempt counter for a fresh execution. */
    void beginAttempt() {
        attempt++;
        state = TransactionState.NOT_STARTED;
        currentStepIndex = -1;
        error = null;
    }

    void setState(TransactionState state) {
        this.state = state;
    }

    void setCurrentStepIndex(int currentStepIndex) {
        this.currentStepIndex = currentStepIndex;
    }

    void setError(Error error) {
        this.error = error;
    }
}
