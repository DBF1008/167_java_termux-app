package com.termux.app.install;

import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates the install/init lifecycle through a sequence of {@link InstallStep}s,
 * managing state transitions, concurrency control, and error handling.
 * <p>
 * Thread safety:
 * <ul>
 *   <li>Uses {@link ReentrantLock} to prevent concurrent installations.
 *       {@code tryLock()} is used so callers never block — they receive an
 *       immediate error if another install is running.</li>
 *   <li>State transitions are validated via
 *       {@link InstallState#isValidTransition(InstallState, InstallState)}.</li>
 *   <li>Failed steps trigger rollback of previously executed steps in reverse order.</li>
 * </ul>
 */
public class InstallCoordinator {

    private static final String LOG_TAG = "InstallCoordinator";

    private final List<InstallStep> fullInstallSteps;
    private final List<InstallStep> storageOnlySteps;
    private final InstallContext context;

    private final AtomicReference<InstallState> currentState =
        new AtomicReference<>(InstallState.IDLE);
    private final ReentrantLock executionLock = new ReentrantLock();
    private final List<InstallStateListener> listeners =
        Collections.synchronizedList(new ArrayList<>());

    /** Tracks which steps have executed successfully (for rollback). */
    private final List<InstallStep> executedSteps = new ArrayList<>();

    /** The last error from a failed pipeline run. */
    private volatile Error lastError;

    public InstallCoordinator(InstallContext context,
                              List<InstallStep> fullInstallSteps,
                              List<InstallStep> storageOnlySteps) {
        this.context = context;
        this.fullInstallSteps = Collections.unmodifiableList(new ArrayList<>(fullInstallSteps));
        this.storageOnlySteps = Collections.unmodifiableList(new ArrayList<>(storageOnlySteps));
    }

    // ─── Listener Management ───────────────────────────────────

    public void addListener(InstallStateListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(InstallStateListener listener) {
        listeners.remove(listener);
    }

    // ─── State Access ──────────────────────────────────────────

    public InstallState getCurrentState() {
        return currentState.get();
    }

    public boolean isRunning() {
        return executionLock.isLocked();
    }

    public Error getLastError() {
        return lastError;
    }

    // ─── Full Bootstrap Install ────────────────────────────────

    /**
     * Runs the full bootstrap installation pipeline.
     * Must be called on a background thread.
     *
     * @return {@code null} on success, or an {@link Error} on failure.
     */
    public Error runFullInstall() {
        if (!executionLock.tryLock()) {
            return InstallErrno.ERRNO_ALREADY_RUNNING.getError();
        }
        try {
            Error error = executePipeline(fullInstallSteps);
            lastError = error;
            notifyInstallComplete(error);
            return error;
        } finally {
            executionLock.unlock();
        }
    }

    // ─── Storage-Only Setup ────────────────────────────────────

    /**
     * Runs only the storage symlink setup pipeline.
     * Can run independently of bootstrap install but shares the same lock.
     *
     * @return {@code null} on success, or an {@link Error} on failure.
     */
    public Error runStorageSetup() {
        if (!executionLock.tryLock()) {
            return InstallErrno.ERRNO_ALREADY_RUNNING.getError();
        }
        try {
            Error error = executePipeline(storageOnlySteps);
            lastError = error;
            notifyInstallComplete(error);
            return error;
        } finally {
            executionLock.unlock();
        }
    }

    // ─── Retry After Failure ───────────────────────────────────

    /**
     * Resets from {@link InstallState#FAILED} to {@link InstallState#IDLE}
     * and re-runs the full install pipeline.
     *
     * @return {@code null} on success, or an {@link Error} on failure.
     */
    public Error retryFullInstall() {
        Error resetError = resetFromFailed();
        if (resetError != null) return resetError;
        return runFullInstall();
    }

    // ─── Core Pipeline Engine ──────────────────────────────────

    private Error executePipeline(List<InstallStep> steps) {
        executedSteps.clear();

        for (InstallStep step : steps) {
            Logger.logInfo(LOG_TAG, "Executing step: " + step.getName());
            InstallState targetState = step.getResultingState();

            // Notify listeners about upcoming state transition
            notifyStateChanging(targetState);

            // Execute the step
            Error error = step.execute(context);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG,
                    "Step \"" + step.getName() + "\" failed:\n" + error);
                transitionTo(InstallState.FAILED);
                rollbackExecutedSteps();
                return error;
            }

            executedSteps.add(step);

            // Check for SkippableStep: installation not needed
            if (step instanceof SkippableStep
                && ((SkippableStep) step).shouldSkipRemaining()) {
                Logger.logInfo(LOG_TAG,
                    "Step \"" + step.getName() + "\" signaled skip; installation not needed.");
                transitionTo(InstallState.SUCCESS);
                return null;
            }

            // Transition to the step's resulting state
            transitionTo(targetState);
        }

        transitionTo(InstallState.SUCCESS);
        return null;
    }

    // ─── State Transition ──────────────────────────────────────

    private synchronized boolean transitionTo(InstallState newState) {
        InstallState current = currentState.get();
        if (!InstallState.isValidTransition(current, newState)) {
            Logger.logError(LOG_TAG,
                "Invalid state transition: " + current.getName()
                    + " -> " + newState.getName());
            return false;
        }
        currentState.set(newState);
        notifyStateChanged(newState);
        return true;
    }

    // ─── Rollback ──────────────────────────────────────────────

    private void rollbackExecutedSteps() {
        for (int i = executedSteps.size() - 1; i >= 0; i--) {
            InstallStep step = executedSteps.get(i);
            try {
                Error rollbackError = step.rollback(context);
                if (rollbackError != null) {
                    Logger.logErrorExtended(LOG_TAG,
                        "Rollback of step \"" + step.getName()
                            + "\" failed:\n" + rollbackError);
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                    "Exception during rollback of step \""
                        + step.getName() + "\"", e);
            }
        }
        executedSteps.clear();
    }

    // ─── Reset ─────────────────────────────────────────────────

    private Error resetFromFailed() {
        InstallState current = currentState.get();
        if (current != InstallState.FAILED) {
            return InstallErrno.ERRNO_INVALID_RETRY_STATE.getError(current.getName());
        }
        transitionTo(InstallState.IDLE);
        return null;
    }

    // ─── Notification ──────────────────────────────────────────

    private void notifyStateChanging(InstallState upcomingState) {
        for (InstallStateListener l : getListenerSnapshot()) {
            try {
                l.onStateChanging(upcomingState);
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Listener threw on onStateChanging: " + e.getMessage());
            }
        }
    }

    private void notifyStateChanged(InstallState newState) {
        for (InstallStateListener l : getListenerSnapshot()) {
            try {
                l.onStateChanged(newState);
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Listener threw on onStateChanged: " + e.getMessage());
            }
        }
    }

    private void notifyInstallComplete(Error error) {
        boolean success = (error == null);
        for (InstallStateListener l : getListenerSnapshot()) {
            try {
                l.onInstallComplete(success, error);
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Listener threw on onInstallComplete: " + e.getMessage());
            }
        }
    }

    private List<InstallStateListener> getListenerSnapshot() {
        synchronized (listeners) {
            return new ArrayList<>(listeners);
        }
    }

    // ─── Testing Support ───────────────────────────────────────

    /**
     * Resets the coordinator state to IDLE. Intended for testing only.
     */
    void resetForTesting() {
        currentState.set(InstallState.IDLE);
        executedSteps.clear();
        lastError = null;
    }
}
