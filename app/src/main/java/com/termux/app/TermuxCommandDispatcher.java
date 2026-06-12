package com.termux.app;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.data.DataUtils;
import com.termux.shared.errors.Errno;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.shell.command.ExecutionCommand.ShellCreateMode;
import com.termux.shared.shell.command.runner.ExecutionCommandRunner;
import com.termux.shared.shell.command.runner.ExecutionCommandRunnerClient;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.shared.termux.plugins.TermuxPluginUtils;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Central dispatcher that unifies the lifecycle management of execution commands
 * across all runner types ({@link TermuxSession} for foreground, {@link AppShell} for background).
 *
 * This class eliminates the previously duplicated logic in {@link TermuxService} for:
 * - Shell name derivation from executable path
 * - Shell create mode validation and existing shell lookup
 * - Shell environment setup
 * - Pending plugin execution command tracking
 * - Post-creation registration (typed list + pending list cleanup + notification update)
 * - Post-exit result processing (plugin result + typed list cleanup + notification update)
 * - Kill-all on service shutdown
 *
 * {@link TermuxService} delegates all command lifecycle operations to this dispatcher.
 * The dispatcher implements {@link ExecutionCommandRunnerClient} so that runner exit
 * callbacks are handled uniformly regardless of runner type.
 */
public class TermuxCommandDispatcher implements ExecutionCommandRunnerClient {

    private final TermuxService mService;
    private final TermuxShellManager mShellManager;
    private final Handler mHandler;

    private static final String LOG_TAG = "TermuxCommandDispatcher";

    public TermuxCommandDispatcher(@NonNull TermuxService service,
                                   @NonNull TermuxShellManager shellManager,
                                   @NonNull Handler handler) {
        mService = service;
        mShellManager = shellManager;
        mHandler = handler;
    }



    // ========================================================================
    //  Unified execution entry point
    // ========================================================================

    /**
     * Execute a command by dispatching it to the appropriate runner type.
     * This replaces the previously separate {@code executeTermuxTaskCommand} and
     * {@code executeTermuxSessionCommand} methods.
     *
     * @param executionCommand The {@link ExecutionCommand} to execute.
     */
    public void execute(@NonNull ExecutionCommand executionCommand) {
        // Derive shell name from executable if not already set
        if (executionCommand.shellName == null && executionCommand.executable != null)
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable);

        // Validate shell create mode
        ShellCreateMode shellCreateMode = processShellCreateMode(executionCommand);
        if (shellCreateMode == null) return;

        if (Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
            executeAppShellCommand(executionCommand, shellCreateMode);
        } else if (Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner)) {
            executeTermuxSessionCommand(executionCommand, shellCreateMode);
        } else {
            String errmsg = mService.getString(R.string.error_termux_service_unsupported_execution_command_runner, executionCommand.runner);
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(mService, LOG_TAG, executionCommand, false);
        }
    }

    /**
     * Execute an {@link AppShell} command, reusing an existing task if applicable.
     */
    private void executeAppShellCommand(@NonNull ExecutionCommand executionCommand,
                                        @NonNull ShellCreateMode shellCreateMode) {
        Logger.logDebug(LOG_TAG, "Executing background \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask command");

        AppShell existingTask = null;
        if (ShellCreateMode.NO_SHELL_WITH_NAME.equals(shellCreateMode)) {
            existingTask = mService.getTermuxTaskForShellName(executionCommand.shellName);
            if (existingTask != null)
                Logger.logVerbose(LOG_TAG, "Existing TermuxTask with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
            else
                Logger.logVerbose(LOG_TAG, "No existing TermuxTask with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
        }

        if (existingTask == null)
            createTermuxTask(executionCommand);
    }

    /**
     * Execute a {@link TermuxSession} command, reusing an existing session if applicable,
     * and handling session actions.
     */
    private void executeTermuxSessionCommand(@NonNull ExecutionCommand executionCommand,
                                             @NonNull ShellCreateMode shellCreateMode) {
        Logger.logDebug(LOG_TAG, "Executing foreground \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession command");

        TermuxSession existingSession = null;
        if (ShellCreateMode.NO_SHELL_WITH_NAME.equals(shellCreateMode)) {
            existingSession = mService.getTermuxSessionForShellName(executionCommand.shellName);
            if (existingSession != null)
                Logger.logVerbose(LOG_TAG, "Existing TermuxSession with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
            else
                Logger.logVerbose(LOG_TAG, "No existing TermuxSession with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
        }

        TermuxSession newTermuxSession = existingSession;
        if (newTermuxSession == null)
            newTermuxSession = createTermuxSession(executionCommand);
        if (newTermuxSession == null) return;

        mService.handleSessionAction(DataUtils.getIntFromString(executionCommand.sessionAction,
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY),
            newTermuxSession.getTerminalSession());
    }



    // ========================================================================
    //  Shell creation
    // ========================================================================

    /**
     * Create a background {@link AppShell} task.
     *
     * @param executionCommand The {@link ExecutionCommand} for the task.
     * @return The created {@link AppShell}, or {@code null} if creation failed.
     */
    @Nullable
    public synchronized AppShell createTermuxTask(ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask");

        if (!Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(LOG_TAG, "Ignoring wrong runner \"" + executionCommand.runner + "\" command passed to createTermuxTask()");
            return null;
        }

        executionCommand.setShellCommandShellEnvironment = true;

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        AppShell newTermuxTask = AppShell.execute(mService, executionCommand, this,
            new TermuxShellEnvironment(), null, false);
        if (newTermuxTask == null) {
            onShellCreationFailed(executionCommand);
            return null;
        }

        onShellCreated(newTermuxTask);
        return newTermuxTask;
    }

    /**
     * Create a foreground {@link TermuxSession}.
     *
     * @param executionCommand The {@link ExecutionCommand} for the session.
     * @return The created {@link TermuxSession}, or {@code null} if creation failed.
     */
    @Nullable
    public synchronized TermuxSession createTermuxSession(ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession");

        if (!Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(LOG_TAG, "Ignoring wrong runner \"" + executionCommand.runner + "\" command passed to createTermuxSession()");
            return null;
        }

        executionCommand.setShellCommandShellEnvironment = true;
        executionCommand.terminalTranscriptRows = mService.getProperties().getTerminalTranscriptRows();

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        // If the execution command was started for a plugin, only then will the stdout be set.
        // Otherwise if command was manually started by the user like by adding a new terminal session,
        // then no need to set stdout.
        TermuxSession newTermuxSession = TermuxSession.execute(mService, executionCommand,
            mService.getTermuxTerminalSessionClient(), this,
            new TermuxShellEnvironment(), null, executionCommand.isPluginExecutionCommand);
        if (newTermuxSession == null) {
            onShellCreationFailed(executionCommand);
            return null;
        }

        onShellCreated(newTermuxSession);

        // Notify TermuxSessionsListViewController that sessions list has been updated if
        // activity is in foreground
        mService.notifyTermuxSessionListChanged();

        mService.updateNotification();

        // No need to recreate the activity since it likely just started and theme should already have applied
        TermuxActivity.updateTermuxActivityStyling(mService, false);

        return newTermuxSession;
    }

    /**
     * Common post-creation registration: add runner to typed list, remove from pending,
     * update notification.
     */
    private void onShellCreated(@NonNull ExecutionCommandRunner runner) {
        mShellManager.registerRunner(runner);
        mService.updateNotification();
    }

    /**
     * Common post-creation-failure handling: log error and process plugin error if applicable.
     */
    private void onShellCreationFailed(@NonNull ExecutionCommand executionCommand) {
        Logger.logError(LOG_TAG, "Failed to execute new command for:\n" + executionCommand.getCommandIdAndLabelLogString());
        if (executionCommand.isPluginExecutionCommand) {
            TermuxPluginUtils.processPluginExecutionCommandError(mService, LOG_TAG, executionCommand, false);
        } else {
            Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs");
            Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString());
        }
    }



    // ========================================================================
    //  Unified exit callback (ExecutionCommandRunnerClient)
    // ========================================================================

    /**
     * Called when any runner exits. This replaces the previously separate
     * {@code onAppShellExited} and {@code onTermuxSessionExited} callbacks.
     */
    @Override
    public void onRunnerExited(ExecutionCommandRunner runner) {
        if (runner == null) return;
        mHandler.post(() -> handleRunnerExited(runner));
    }

    private void handleRunnerExited(@NonNull ExecutionCommandRunner runner) {
        ExecutionCommand executionCommand = runner.getExecutionCommand();

        Logger.logVerbose(LOG_TAG, "Runner exited callback for \"" + executionCommand.getCommandIdAndLabelLogString() + "\"");

        // If the execution command was started for a plugin, then process the results
        if (executionCommand.isPluginExecutionCommand)
            TermuxPluginUtils.processPluginExecutionCommandResult(mService, LOG_TAG, executionCommand);

        // Remove from the appropriate typed list
        mShellManager.unregisterRunner(runner);

        // Notify session list changes if this was a TermuxSession
        if (runner instanceof TermuxSession)
            mService.notifyTermuxSessionListChanged();

        mService.updateNotification();
    }



    // ========================================================================
    //  Shell create mode validation
    // ========================================================================

    /**
     * Validate the shell create mode of the execution command.
     *
     * @return The validated {@link ShellCreateMode}, or {@code null} if invalid (error already processed).
     */
    @Nullable
    private ShellCreateMode processShellCreateMode(@NonNull ExecutionCommand executionCommand) {
        if (ShellCreateMode.ALWAYS.equalsMode(executionCommand.shellCreateMode))
            return ShellCreateMode.ALWAYS; // Default
        else if (ShellCreateMode.NO_SHELL_WITH_NAME.equalsMode(executionCommand.shellCreateMode))
            if (DataUtils.isNullOrEmpty(executionCommand.shellName)) {
                TermuxPluginUtils.setAndProcessPluginExecutionCommandError(mService, LOG_TAG, executionCommand, false,
                    mService.getString(R.string.error_termux_service_execution_command_shell_name_unset, executionCommand.shellCreateMode));
                return null;
            } else {
               return ShellCreateMode.NO_SHELL_WITH_NAME;
            }
        else {
            TermuxPluginUtils.setAndProcessPluginExecutionCommandError(mService, LOG_TAG, executionCommand, false,
                mService.getString(R.string.error_termux_service_unsupported_execution_command_shell_create_mode, executionCommand.shellCreateMode));
            return null;
        }
    }



    // ========================================================================
    //  Kill all
    // ========================================================================

    /**
     * Kill all running execution commands. This replaces the previously monolithic
     * {@code killAllTermuxExecutionCommands} method.
     *
     * @param wantsToStop Whether the service is stopping intentionally (user initiated).
     */
    public synchronized void killAllExecutionCommands(boolean wantsToStop) {
        Logger.logDebug(LOG_TAG, "Killing TermuxSessions=" + mShellManager.mTermuxSessions.size() +
            ", TermuxTasks=" + mShellManager.mTermuxTasks.size() +
            ", PendingPluginExecutionCommands=" + mShellManager.mPendingPluginExecutionCommands.size());

        // Kill foreground sessions
        List<TermuxSession> termuxSessions = new ArrayList<>(mShellManager.mTermuxSessions);
        for (int i = 0; i < termuxSessions.size(); i++) {
            ExecutionCommand executionCommand = termuxSessions.get(i).getExecutionCommand();
            boolean processResult = wantsToStop || executionCommand.isPluginExecutionCommandWithPendingResult();
            termuxSessions.get(i).killIfExecuting(mService, processResult);
            if (!processResult)
                mShellManager.mTermuxSessions.remove(termuxSessions.get(i));
        }

        // Kill background tasks (only those with pending plugin results)
        List<AppShell> termuxTasks = new ArrayList<>(mShellManager.mTermuxTasks);
        for (int i = 0; i < termuxTasks.size(); i++) {
            ExecutionCommand executionCommand = termuxTasks.get(i).getExecutionCommand();
            if (executionCommand.isPluginExecutionCommandWithPendingResult())
                termuxTasks.get(i).killIfExecuting(mService, true);
            else
                mShellManager.mTermuxTasks.remove(termuxTasks.get(i));
        }

        // Process any pending plugin execution commands that never got started
        List<ExecutionCommand> pendingCommands = new ArrayList<>(mShellManager.mPendingPluginExecutionCommands);
        for (int i = 0; i < pendingCommands.size(); i++) {
            ExecutionCommand executionCommand = pendingCommands.get(i);
            if (!executionCommand.shouldNotProcessResults() && executionCommand.isPluginExecutionCommandWithPendingResult()) {
                if (executionCommand.setStateFailed(Errno.ERRNO_CANCELLED.getCode(), mService.getString(com.termux.shared.R.string.error_execution_cancelled))) {
                    TermuxPluginUtils.processPluginExecutionCommandResult(mService, LOG_TAG, executionCommand);
                }
            }
        }
    }

}
