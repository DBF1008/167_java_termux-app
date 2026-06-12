package com.termux.shared.shell.command.runner;

/**
 * Callback interface for when an {@link ExecutionCommandRunner} exits.
 * This replaces the previous runner-specific client interfaces
 * ({@code AppShell.AppShellClient}, {@code TermuxSession.TermuxSessionClient})
 * so that the {@link com.termux.app.TermuxCommandDispatcher} can handle all runner types uniformly.
 */
public interface ExecutionCommandRunnerClient {

    /**
     * Called when the runner has exited (normally or due to being killed).
     *
     * @param runner The {@link ExecutionCommandRunner} that exited.
     */
    void onRunnerExited(ExecutionCommandRunner runner);

}
