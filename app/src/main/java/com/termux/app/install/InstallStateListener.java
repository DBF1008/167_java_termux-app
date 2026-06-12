package com.termux.app.install;

import com.termux.shared.errors.Error;

/**
 * Observer interface for install lifecycle state changes.
 * <p>
 * Implementations can update UI (progress dialogs, notifications)
 * or trigger follow-up actions when the coordinator transitions
 * between states.
 */
public interface InstallStateListener {

    /**
     * Called just before a step executes, indicating the state the coordinator
     * is about to transition into.
     *
     * @param upcomingState The state that will be entered if the step succeeds.
     */
    default void onStateChanging(InstallState upcomingState) {}

    /**
     * Called after a state transition has completed.
     *
     * @param newState The state that was just entered.
     */
    default void onStateChanged(InstallState newState) {}

    /**
     * Called when the entire installation pipeline has completed.
     *
     * @param success {@code true} if installation succeeded.
     * @param error The error if installation failed, or {@code null}.
     */
    default void onInstallComplete(boolean success, Error error) {}
}
