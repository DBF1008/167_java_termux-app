package com.termux.shared.shell.command.runner;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.shell.command.ExecutionCommand;

/**
 * Common interface for all execution command runners ({@link com.termux.shared.shell.command.runner.app.AppShell},
 * {@link com.termux.shared.termux.shell.command.runner.terminal.TermuxSession}, etc.).
 *
 * This allows the {@link com.termux.app.TermuxCommandDispatcher} to manage the lifecycle of
 * any runner type uniformly: registration, result processing, kill, and cleanup.
 */
public interface ExecutionCommandRunner {

    /**
     * Get the {@link ExecutionCommand} associated with this runner.
     *
     * @return Returns the {@link ExecutionCommand}.
     */
    @NonNull
    ExecutionCommand getExecutionCommand();

    /**
     * Kill this runner by sending SIGKILL to its process if it is still executing.
     *
     * @param context The {@link Context} for operations.
     * @param processResult If set to {@code true}, then the result will be processed
     *                      (failure state set and callback invoked).
     */
    void killIfExecuting(@NonNull Context context, boolean processResult);

}
