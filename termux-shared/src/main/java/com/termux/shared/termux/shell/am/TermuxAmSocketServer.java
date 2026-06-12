package com.termux.shared.termux.shell.am;

import android.content.Context;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.socket.local.LocalClientSocket;
import com.termux.shared.net.socket.local.LocalServerSocket;
import com.termux.shared.net.socket.local.LocalSocketManager;
import com.termux.shared.net.socket.local.LocalSocketManagerClientBase;
import com.termux.shared.net.socket.local.LocalSocketRunConfig;
import com.termux.shared.shell.am.AmSocketServerRunConfig;
import com.termux.shared.shell.am.AmSocketServer;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.plugins.TermuxPluginUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.shell.command.environment.TermuxAppShellEnvironment;

/**
 * A wrapper for {@link AmSocketServer} for termux-app usage.
 *
 * The static {@link #termuxAmSocketServer} variable stores the {@link LocalSocketManager} for the
 * {@link AmSocketServer}.
 *
 * The {@link TermuxAmSocketServerClient} extends the {@link AmSocketServer.AmSocketServerClient}
 * class to also show plugin error notifications for errors and disallowed client connections in
 * addition to logging the messages to logcat, which are only logged by {@link LocalSocketManagerClientBase}
 * if log level is debug or higher for privacy issues.
 *
 * It uses a filesystem socket server with the socket file at
 * {@link TermuxConstants.TERMUX_APP#TERMUX_AM_SOCKET_FILE_PATH}. It would normally only allow
 * processes belonging to the termux user and root user to connect to it. If commands are sent by the
 * root user, then the am commands executed will be run as the termux user and its permissions,
 * capabilities and selinux context instead of root.
 *
 * The `$PREFIX/bin/termux-am` client connects to the server via `$PREFIX/bin/termux-am-socket` to
 * run the am commands. It provides similar functionality to "$PREFIX/bin/am"
 * (and "/system/bin/am"), but should be faster since it does not require starting a dalvik vm for
 * every command as done by "am" via termux/TermuxAm.
 *
 * The server is started by termux-app Application class but is not started if
 * {@link TermuxPropertyConstants#KEY_RUN_TERMUX_AM_SOCKET_SERVER} is `false` which can be done by
 * adding the prop with value "false" to the "~/.termux/termux.properties" file. Changes can be
 * applied at runtime by reloading settings (for example via `termux-reload-settings`), which calls
 * {@link #updateState(Context)} to start or stop the server and update the value exported to new
 * shell sessions and tasks. Already running shells/tasks keep the value they were started with.
 *
 * The current state of the server can be checked with the
 * {@link TermuxAppShellEnvironment#ENV_TERMUX_APP__AM_SOCKET_SERVER_ENABLED} env variable, which is exported
 * for all shell sessions and tasks.
 *
 * https://github.com/termux/termux-am-socket
 * https://github.com/termux/TermuxAm
 */
public class TermuxAmSocketServer {

    public static final String LOG_TAG = "TermuxAmSocketServer";

    public static final String TITLE = "TermuxAm";

    /** The static instance for the {@link TermuxAmSocketServer} {@link LocalSocketManager}. */
    private static LocalSocketManager termuxAmSocketServer;

    /** Whether {@link TermuxAmSocketServer} is enabled and running or not. */
    @Keep
    protected static Boolean TERMUX_APP_AM_SOCKET_SERVER_ENABLED;

    /**
     * Setup the {@link AmSocketServer} {@link LocalServerSocket} and start listening for
     * new {@link LocalClientSocket} if enabled.
     *
     * @param context The {@link Context} for {@link LocalSocketManager}.
     */
    public static void setupTermuxAmSocketServer(@NonNull Context context) {
        // Start termux-am-socket server if enabled by user
        boolean enabled = false;
        if (TermuxAppSharedProperties.getProperties().shouldRunTermuxAmSocketServer()) {
            Logger.logDebug(LOG_TAG, "Starting " + TITLE + " socket server since its enabled");
            start(context);
            if (termuxAmSocketServer != null && termuxAmSocketServer.isRunning()) {
                enabled = true;
                Logger.logDebug(LOG_TAG, TITLE + " socket server successfully started");
            }
        } else {
            Logger.logDebug(LOG_TAG, "Not starting " + TITLE + " socket server since its not enabled");
        }

        // Sync the exported state to the actual server state. After app start this can be updated
        // again via a settings hot-reload (updateState); already running shells/tasks keep the env
        // snapshot they captured, while new shells/tasks read the updated value.
        setTermuxAppAMSocketServerEnabled(context, enabled);
    }

    /**
     * Create the {@link AmSocketServer} {@link LocalServerSocket} and start listening for new {@link LocalClientSocket}.
     */
    public static synchronized void start(@NonNull Context context) {
        stop();

        AmSocketServerRunConfig amSocketServerRunConfig = new AmSocketServerRunConfig(TITLE,
            TermuxConstants.TERMUX_APP.TERMUX_AM_SOCKET_FILE_PATH, new TermuxAmSocketServerClient());

        termuxAmSocketServer = AmSocketServer.start(context, amSocketServerRunConfig);
    }

    /**
     * Stop the {@link AmSocketServer} {@link LocalServerSocket} and stop listening for new {@link LocalClientSocket}.
     */
    public static synchronized void stop() {
        if (termuxAmSocketServer != null) {
            Error error = termuxAmSocketServer.stop();
            if (error != null) {
                termuxAmSocketServer.onError(error);
            }
            termuxAmSocketServer = null;
        }
    }
    
    /**
     * Update the state of the {@link AmSocketServer} {@link LocalServerSocket} depending on the
     * current value of {@link TermuxPropertyConstants#KEY_RUN_TERMUX_AM_SOCKET_SERVER} read from the
     * {@link TermuxAppSharedProperties} in-memory cache (call
     * {@link TermuxAppSharedProperties#loadTermuxPropertiesFromDisk()} first to pick up file changes).
     *
     * This is the safe hot-reload primitive: it starts or stops the server to match the property and
     * then syncs the exported {@link #TERMUX_APP_AM_SOCKET_SERVER_ENABLED} value and the
     * {@link TermuxAppShellEnvironment#ENV_TERMUX_APP__AM_SOCKET_SERVER_ENABLED} env so that new
     * shell sessions and tasks read the updated value. Already running shells/tasks keep the env
     * snapshot they captured when they were created.
     */
    public static synchronized void updateState(@NonNull Context context) {
        boolean shouldRun = TermuxAppSharedProperties.getProperties().shouldRunTermuxAmSocketServer();
        boolean isCurrentlyRunning = termuxAmSocketServer != null;

        switch (getSocketServerActionForState(shouldRun, isCurrentlyRunning)) {
            case START:
                Logger.logDebug(LOG_TAG, "updateState: Starting " + TITLE + " socket server");
                start(context);
                break;
            case STOP:
                Logger.logDebug(LOG_TAG, "updateState: Disabling " + TITLE + " socket server");
                stop();
                break;
            case NONE:
                break;
        }

        // Sync exported state to the actual server state. start() may fail (e.g. socket lib load or
        // socket creation error) leaving the server stopped, so derive the value from the real state.
        setTermuxAppAMSocketServerEnabled(context,
            termuxAmSocketServer != null && termuxAmSocketServer.isRunning());
    }

    /** The action required to reconcile the {@link AmSocketServer} with the desired enabled state. */
    public enum SocketServerAction {
        /** Server should be running but isn't, so it must be started. */
        START,
        /** Server is running but shouldn't be, so it must be stopped. */
        STOP,
        /** Server is already in the desired state, so nothing needs to be done. */
        NONE
    }

    /**
     * Get the {@link SocketServerAction} required to reconcile the server with the desired state.
     * This is a pure function with no side effects so the hot-reload policy can be unit tested
     * without touching the native socket layer.
     *
     * @param shouldRun Whether the server should be running as per the user property.
     * @param isCurrentlyRunning Whether a server instance currently exists.
     * @return Returns the {@link SocketServerAction} to apply.
     */
    public static SocketServerAction getSocketServerActionForState(boolean shouldRun, boolean isCurrentlyRunning) {
        if (shouldRun && !isCurrentlyRunning) return SocketServerAction.START;
        if (!shouldRun && isCurrentlyRunning) return SocketServerAction.STOP;
        return SocketServerAction.NONE;
    }

    /**
     * Set the exported {@link #TERMUX_APP_AM_SOCKET_SERVER_ENABLED} value and sync the
     * {@link TermuxAppShellEnvironment#ENV_TERMUX_APP__AM_SOCKET_SERVER_ENABLED} env variable read by
     * new shell sessions and tasks. Already running shells/tasks keep their env snapshot since a
     * running process's environment cannot be mutated.
     *
     * @param context The {@link Context} for {@link TermuxAppShellEnvironment}.
     * @param enabled Whether the server is enabled and running.
     */
    public static synchronized void setTermuxAppAMSocketServerEnabled(@NonNull Context context, boolean enabled) {
        TERMUX_APP_AM_SOCKET_SERVER_ENABLED = enabled;
        TermuxAppShellEnvironment.updateTermuxAppAMSocketServerEnabled(context);
    }
    
    /**
     * Get {@link #termuxAmSocketServer}.
     */
    public static synchronized LocalSocketManager getTermuxAmSocketServer() {
        return termuxAmSocketServer;
    }

    /**
     * Show an error notification on the {@link TermuxConstants#TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_ID}
     * {@link TermuxConstants#TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_NAME} with a call
     * to {@link TermuxPluginUtils#sendPluginCommandErrorNotification(Context, String, CharSequence, String, String)}.
     *
     * @param context The {@link Context} to send the notification with.
     * @param error The {@link Error} generated.
     * @param localSocketRunConfig The {@link LocalSocketRunConfig} for {@link LocalSocketManager}.
     * @param clientSocket The optional {@link LocalClientSocket} for which the error was generated.
     */
    public static synchronized void showErrorNotification(@NonNull Context context, @NonNull Error error,
                                                          @NonNull LocalSocketRunConfig localSocketRunConfig,
                                                          @Nullable LocalClientSocket clientSocket) {
        TermuxPluginUtils.sendPluginCommandErrorNotification(context, LOG_TAG,
            localSocketRunConfig.getTitle() + " Socket Server Error", error.getMinimalErrorString(),
            LocalSocketManager.getErrorMarkdownString(error, localSocketRunConfig, clientSocket));
    }



    public static Boolean getTermuxAppAMSocketServerEnabled(@NonNull Context currentPackageContext) {
        boolean isTermuxApp = TermuxConstants.TERMUX_PACKAGE_NAME.equals(currentPackageContext.getPackageName());
        if (isTermuxApp) {
            return TERMUX_APP_AM_SOCKET_SERVER_ENABLED;
        } else {
            // Currently, unsupported since plugin app processes don't know that value is set in termux
            // app process TermuxAmSocketServer class. A binder API or a way to check if server is actually
            // running needs to be used. Long checks would also not be possible on main application thread
            return null;
        }

    }





    /** Enhanced implementation for {@link AmSocketServer.AmSocketServerClient} for {@link TermuxAmSocketServer}. */
    public static class TermuxAmSocketServerClient extends AmSocketServer.AmSocketServerClient {

        public static final String LOG_TAG = "TermuxAmSocketServerClient";

        @Nullable
        @Override
        public Thread.UncaughtExceptionHandler getLocalSocketManagerClientThreadUEH(
            @NonNull LocalSocketManager localSocketManager) {
            // Use termux crash handler for socket listener thread just like used for main app process thread.
            return TermuxCrashUtils.getCrashHandler(localSocketManager.getContext());
        }

        @Override
        public void onError(@NonNull LocalSocketManager localSocketManager,
                            @Nullable LocalClientSocket clientSocket, @NonNull Error error) {
            // Don't show notification if server is not running since errors may be triggered
            // when server is stopped and server and client sockets are closed.
            if (localSocketManager.isRunning()) {
                TermuxAmSocketServer.showErrorNotification(localSocketManager.getContext(), error,
                    localSocketManager.getLocalSocketRunConfig(), clientSocket);
            }

            // But log the exception
            super.onError(localSocketManager, clientSocket, error);
        }

        @Override
        public void onDisallowedClientConnected(@NonNull LocalSocketManager localSocketManager,
                                                @NonNull LocalClientSocket clientSocket, @NonNull Error error) {
            // Always show notification and log error regardless of if server is running or not
            TermuxAmSocketServer.showErrorNotification(localSocketManager.getContext(), error,
                localSocketManager.getLocalSocketRunConfig(), clientSocket);
            super.onDisallowedClientConnected(localSocketManager, clientSocket, error);
        }



        @Override
        protected String getLogTag() {
            return LOG_TAG;
        }

    }

}
