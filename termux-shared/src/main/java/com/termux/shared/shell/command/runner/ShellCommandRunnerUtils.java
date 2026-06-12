package com.termux.shared.shell.command.runner;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.termux.shared.R;
import com.termux.shared.errors.Errno;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.ExecutionState;

/**
 * Shared lifecycle and result handling for all execution-command runners.
 *
 * Before this existed, the foreground {@code TermuxSession} and background {@code AppShell} runners
 * each kept their own private {@code processXResult()} method and inlined the same
 * {@link ExecutionState} transitions ({@link ExecutionState#EXECUTING}, {@link ExecutionState#EXECUTED},
 * {@link ExecutionState#SUCCESS} and the SIGKILL failure path). Those copies are now consolidated
 * here so every runner shares identical behaviour, in particular the
 * {@link ExecutionCommand#shouldNotProcessResults()} de-duplication latch which guarantees results
 * are processed exactly once.
 */
public final class ShellCommandRunnerUtils {

    private ShellCommandRunnerUtils() {}

    /**
     * Process the result of an {@link ExecutionCommand} once it has finished (whether successfully,
     * with a non-zero exit code, or after a failure).
     *
     * The {@link ExecutionCommand#shouldNotProcessResults()} latch ensures this runs at most once
     * per command, regardless of how many runner paths (normal exit, broken pipe, SIGKILL,
     * start failure, cancellation) call it.
     *
     * If {@code clientExitCallback} is not {@code null}, it is run so the callback host can take over
     * (it is responsible for setting the final {@link ExecutionState#SUCCESS} state itself).
     * Otherwise, if the command has not failed, the state is set to {@link ExecutionState#SUCCESS}
     * here.
     *
     * @param executionCommand The {@link ExecutionCommand} to process.
     * @param logTag The log tag to use for logging.
     * @param clientExitCallback The optional callback to invoke (e.g. notify the runner's client).
     */
    public static void processResult(@NonNull ExecutionCommand executionCommand, @NonNull String logTag,
                                     @Nullable Runnable clientExitCallback) {
        if (executionCommand.shouldNotProcessResults()) {
            Logger.logDebug(logTag, "Ignoring duplicate call to process \"" + executionCommand.getCommandIdAndLabelLogString() + "\" result");
            return;
        }

        Logger.logDebug(logTag, "Processing \"" + executionCommand.getCommandIdAndLabelLogString() + "\" result");

        if (clientExitCallback != null) {
            clientExitCallback.run();
        } else {
            // If a callback is not set and execution command didn't fail, then we set success state now.
            // Otherwise, the callback host can set it himself when its done with the shell.
            if (!executionCommand.isStateFailed())
                executionCommand.setState(ExecutionState.SUCCESS);
        }
    }

    /**
     * Transition the {@link ExecutionCommand} to {@link ExecutionState#EXECUTING} right before the
     * process is started. If the transition is rejected, the command is failed with
     * {@code failureMessageResId} and its result is processed.
     *
     * @param context The {@link Context} for operations.
     * @param executionCommand The {@link ExecutionCommand} to transition.
     * @param logTag The log tag to use for logging.
     * @param failureMessageResId The string resource for the failure message. It must accept a single
     *                            {@code %s} argument for the command id and label.
     * @return Returns {@code true} if the command entered {@link ExecutionState#EXECUTING}, otherwise
     *         {@code false} after the failure has been processed (the caller should abort the start).
     */
    public static boolean enterExecutingOrProcessFailure(@NonNull Context context, @NonNull ExecutionCommand executionCommand,
                                                         @NonNull String logTag, @StringRes int failureMessageResId) {
        if (executionCommand.setState(ExecutionState.EXECUTING))
            return true;

        executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(),
            context.getString(failureMessageResId, executionCommand.getCommandIdAndLabelLogString()));
        processResult(executionCommand, logTag, null);
        return false;
    }

    /**
     * Transition the {@link ExecutionCommand} to {@link ExecutionState#EXECUTED} after the process
     * has finished, unless it has already failed (e.g. a SIGKILL was sent), in which case the natural
     * exit code is not recorded and the command is left in its failed state.
     *
     * @param executionCommand The {@link ExecutionCommand} to transition.
     * @param logTag The log tag to use for logging.
     * @param exitCode The process exit code to record in {@link ExecutionCommand#resultData}.
     * @param beforeSetExecuted An optional hook run after the exit code is recorded but before the
     *                          state is set to {@link ExecutionState#EXECUTED}, used by the terminal
     *                          runner to capture the session transcript. May be {@code null}.
     * @return Returns {@code true} if the command entered {@link ExecutionState#EXECUTED} and its
     *         result should now be processed, otherwise {@code false} (the caller should abort).
     */
    public static boolean markExecutedIfNotFailed(@NonNull ExecutionCommand executionCommand, @NonNull String logTag,
                                                  int exitCode, @Nullable Runnable beforeSetExecuted) {
        // If the execution command has already failed, like SIGKILL was sent, then don't continue
        if (executionCommand.isStateFailed()) {
            Logger.logDebug(logTag, "Ignoring setting \"" + executionCommand.getCommandIdAndLabelLogString() + "\" state to ExecutionState.EXECUTED and processing results since it has already failed");
            return false;
        }

        executionCommand.resultData.exitCode = exitCode;

        if (beforeSetExecuted != null)
            beforeSetExecuted.run();

        return executionCommand.setState(ExecutionState.EXECUTED);
    }

    /**
     * Fail an {@link ExecutionCommand} because its process is being killed with SIGKILL and,
     * optionally, process its result. The actual signal delivery stays in the runner since it differs
     * per runner ({@code Os.kill} vs {@code TerminalSession#finishIfRunning}).
     *
     * @param context The {@link Context} for operations.
     * @param executionCommand The {@link ExecutionCommand} being killed.
     * @param logTag The log tag to use for logging.
     * @param processResult If {@code true}, the result is processed with exit code 137 (SIGKILL).
     * @param beforeProcessResult An optional hook run after the exit code is set but before the result
     *                            is processed, used by the terminal runner to capture the transcript.
     *                            May be {@code null}.
     * @param clientExitCallback The optional callback to pass to {@link #processResult}.
     */
    public static void failKilledAndProcess(@NonNull Context context, @NonNull ExecutionCommand executionCommand,
                                            @NonNull String logTag, boolean processResult,
                                            @Nullable Runnable beforeProcessResult, @Nullable Runnable clientExitCallback) {
        if (executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), context.getString(R.string.error_sending_sigkill_to_process))) {
            if (processResult) {
                executionCommand.resultData.exitCode = 137; // SIGKILL

                if (beforeProcessResult != null)
                    beforeProcessResult.run();

                processResult(executionCommand, logTag, clientExitCallback);
            }
        }
    }

}
