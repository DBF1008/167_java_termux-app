package com.termux.shared.shell.command.runner;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.R;
import com.termux.shared.errors.Errno;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.ExecutionState;

/**
 * Shared template methods for processing results and killing execution command runners.
 *
 * This class eliminates the duplication that previously existed between
 * {@link com.termux.shared.shell.command.runner.app.AppShell AppShell.processAppShellResult} /
 * {@code killIfExecuting} and
 * {@link com.termux.shared.termux.shell.command.runner.terminal.TermuxSession TermuxSession.processTermuxSessionResult} /
 * {@code killIfExecuting}.
 *
 * All runners ({@link ExecutionCommandRunner} implementations) delegate their result processing
 * and kill logic through this handler to ensure consistent behavior.
 */
public class ExecutionCommandResultHandler {

    /**
     * Process the result of an {@link ExecutionCommandRunner} or a standalone {@link ExecutionCommand}.
     *
     * Only one of {@code runner} and {@code executionCommand} should be set.
     * If {@code runner} is set, its {@link ExecutionCommandRunner#getExecutionCommand()} is used.
     *
     * Template:
     * 1. Guard against duplicate processing via {@link ExecutionCommand#shouldNotProcessResults()}.
     * 2. If a runner with a client is provided, invoke {@link ExecutionCommandRunnerClient#onRunnerExited}.
     * 3. Otherwise, set state to {@link ExecutionState#SUCCESS} if not already failed.
     *
     * @param runner The runner that finished, or {@code null} if the command failed before a runner was created.
     * @param executionCommand The execution command, or {@code null} if {@code runner} is provided.
     * @param logTag The log tag for diagnostic messages.
     * @param client The {@link ExecutionCommandRunnerClient} callback, or {@code null}.
     */
    public static void processResult(@Nullable final ExecutionCommandRunner runner,
                                     @Nullable ExecutionCommand executionCommand,
                                     @NonNull final String logTag,
                                     @Nullable final ExecutionCommandRunnerClient client) {
        if (runner != null)
            executionCommand = runner.getExecutionCommand();

        if (executionCommand == null) return;

        if (executionCommand.shouldNotProcessResults()) {
            Logger.logDebug(logTag, "Ignoring duplicate call to process \""
                + executionCommand.getCommandIdAndLabelLogString() + "\" result");
            return;
        }

        Logger.logDebug(logTag, "Processing \""
            + executionCommand.getCommandIdAndLabelLogString() + "\" result");

        if (runner != null && client != null) {
            client.onRunnerExited(runner);
        } else {
            // If a callback is not set and execution command didn't fail, then we set success state now.
            // Otherwise, the callback host can set it himself when it's done with the runner.
            if (!executionCommand.isStateFailed())
                executionCommand.setState(ExecutionState.SUCCESS);
        }
    }



    /**
     * Perform the common kill-if-executing template for any {@link ExecutionCommandRunner}.
     *
     * Template:
     * 1. If the command has already executed, return early.
     * 2. Set state to FAILED.
     * 3. If {@code shouldProcessResult} is true, set exit code to 137 (SIGKILL),
     *    optionally collect output, then process the result.
     * 4. Always call {@code doKill} to actually terminate the process.
     *
     * @param executionCommand The {@link ExecutionCommand} for the runner being killed.
     * @param context The {@link Context} for getting error string resources.
     * @param logTag The log tag for diagnostic messages.
     * @param runnerType A human-readable runner type name for log messages (e.g. "AppShell", "TermuxSession").
     * @param shouldProcessResult Whether to process the result after setting the failed state.
     * @param collectOutputBeforeResult Optional hook to collect output (e.g. terminal transcript) before
     *                                   processing the result. May be {@code null}.
     * @param processResultCallback The callback to invoke for processing the result (typically calls
     *                               {@link #processResult} with the runner's own parameters).
     * @param doKill The callback to actually kill the underlying process (e.g. send SIGKILL).
     */
    public static void performKillIfExecuting(@NonNull final ExecutionCommand executionCommand,
                                               @NonNull final Context context,
                                               @NonNull final String logTag,
                                               @NonNull final String runnerType,
                                               boolean shouldProcessResult,
                                               @Nullable final Runnable collectOutputBeforeResult,
                                               @NonNull final Runnable processResultCallback,
                                               @NonNull final Runnable doKill) {
        // If execution command has already finished executing, then no need to process results or send SIGKILL
        if (executionCommand.hasExecuted()) {
            Logger.logDebug(logTag, "Ignoring sending SIGKILL to \""
                + executionCommand.getCommandIdAndLabelLogString() + "\" " + runnerType
                + " since it has already finished executing");
            return;
        }

        Logger.logDebug(logTag, "Send SIGKILL to \""
            + executionCommand.getCommandIdAndLabelLogString() + "\" " + runnerType);

        if (executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(),
            context.getString(R.string.error_sending_sigkill_to_process))) {
            if (shouldProcessResult) {
                executionCommand.resultData.exitCode = 137; // SIGKILL
                if (collectOutputBeforeResult != null)
                    collectOutputBeforeResult.run();
                processResultCallback.run();
            }
        }

        doKill.run();
    }

}
