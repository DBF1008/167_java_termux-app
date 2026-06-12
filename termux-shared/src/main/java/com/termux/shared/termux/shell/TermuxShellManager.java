package com.termux.shared.termux.shell;

import android.content.Context;
import android.content.Intent;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.ExecutionCommandRunner;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;

import java.util.ArrayList;
import java.util.List;

public class TermuxShellManager {

    private static TermuxShellManager shellManager;

    private static int SHELL_ID = 0;

    protected final Context mContext;

    /**
     * The foreground TermuxSessions which this service manages.
     * Note that this list is observed by an activity, like TermuxActivity.mTermuxSessionListViewController,
     * so any changes must be made on the UI thread and followed by a call to
     * {@link ArrayAdapter#notifyDataSetChanged()}.
     */
    public final List<TermuxSession> mTermuxSessions = new ArrayList<>();

    /**
     * The background TermuxTasks which this service manages.
     */
    public final List<AppShell> mTermuxTasks = new ArrayList<>();

    /**
     * The pending plugin ExecutionCommands that have yet to be processed by this service.
     */
    public final List<ExecutionCommand> mPendingPluginExecutionCommands = new ArrayList<>();

    /**
     * The {@link ExecutionCommand.Runner#APP_SHELL} number after app process was started/restarted.
     */
    public static int APP_SHELL_NUMBER_SINCE_APP_START;

    /**
     * The {@link ExecutionCommand.Runner#TERMINAL_SESSION} number after app process was started/restarted.
     */
    public static int TERMINAL_SESSION_NUMBER_SINCE_APP_START;



    public TermuxShellManager(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Initialize the {@link #shellManager}.
     *
     * @param context The {@link Context} for operations.
     * @return Returns the {@link TermuxShellManager}.
     */
    public static TermuxShellManager init(@NonNull Context context) {
        if (shellManager == null)
            shellManager = new TermuxShellManager(context);

        return shellManager;
    }

    /**
     * Get the {@link #shellManager}.
     *
     * @return Returns the {@link TermuxShellManager}.
     */
    public static TermuxShellManager getShellManager() {
        return shellManager;
    }


    public synchronized static void onActionBootCompleted(@NonNull Context context, @NonNull Intent intent) {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        if (preferences == null) return;

        // Ensure any shells started after boot have valid ENV_SHELL_CMD__APP_SHELL_NUMBER_SINCE_BOOT and
        // ENV_SHELL_CMD__TERMINAL_SESSION_NUMBER_SINCE_BOOT exported
        preferences.resetAppShellNumberSinceBoot();
        preferences.resetTerminalSessionNumberSinceBoot();
    }

    public static void onAppExit(@NonNull Context context) {
        // Ensure any shells started after boot have valid ENV_SHELL_CMD__APP_SHELL_NUMBER_SINCE_APP_START and
        // ENV_SHELL_CMD__TERMINAL_SESSION_NUMBER_SINCE_APP_START exported
        APP_SHELL_NUMBER_SINCE_APP_START = 0;
        TERMINAL_SESSION_NUMBER_SINCE_APP_START = 0;
    }

    public static synchronized int getNextShellId() {
        return SHELL_ID++;
    }

    public static synchronized int getAndIncrementAppShellNumberSinceAppStart() {
        // Keep value at MAX_VALUE on integer overflow and not 0, since not first shell
        int curValue = APP_SHELL_NUMBER_SINCE_APP_START;
        if (curValue < 0) curValue = Integer.MAX_VALUE;

        APP_SHELL_NUMBER_SINCE_APP_START = curValue + 1;
        if (APP_SHELL_NUMBER_SINCE_APP_START < 0) APP_SHELL_NUMBER_SINCE_APP_START = Integer.MAX_VALUE;
        return curValue;
    }

    public static synchronized int getAndIncrementTerminalSessionNumberSinceAppStart() {
        // Keep value at MAX_VALUE on integer overflow and not 0, since not first shell
        int curValue = TERMINAL_SESSION_NUMBER_SINCE_APP_START;
        if (curValue < 0) curValue = Integer.MAX_VALUE;

        TERMINAL_SESSION_NUMBER_SINCE_APP_START = curValue + 1;
        if (TERMINAL_SESSION_NUMBER_SINCE_APP_START < 0) TERMINAL_SESSION_NUMBER_SINCE_APP_START = Integer.MAX_VALUE;
        return curValue;
    }



    /**
     * Register a newly created runner in the appropriate typed list and remove its
     * {@link ExecutionCommand} from the pending plugin execution commands list.
     *
     * @param runner The {@link ExecutionCommandRunner} that was just created.
     */
    public synchronized void registerRunner(ExecutionCommandRunner runner) {
        if (runner == null) return;
        ExecutionCommand executionCommand = runner.getExecutionCommand();

        if (runner instanceof TermuxSession) {
            mTermuxSessions.add((TermuxSession) runner);
        } else if (runner instanceof AppShell) {
            mTermuxTasks.add((AppShell) runner);
        }

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mPendingPluginExecutionCommands.remove(executionCommand);
    }

    /**
     * Unregister a runner from the appropriate typed list.
     *
     * @param runner The {@link ExecutionCommandRunner} that exited.
     */
    public synchronized void unregisterRunner(ExecutionCommandRunner runner) {
        if (runner == null) return;

        if (runner instanceof TermuxSession) {
            mTermuxSessions.remove(runner);
        } else if (runner instanceof AppShell) {
            mTermuxTasks.remove(runner);
        }
    }

}
