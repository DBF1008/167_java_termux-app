package com.termux.app.utils;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.app.api.file.FileReceiverActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.am.TermuxAmSocketServer;

/**
 * Utilities to safely hot-reload Termux app settings without restarting the app.
 *
 * A reload reconciles the process-global components that depend on {@code termux.properties} with
 * their current values:
 * <ul>
 *     <li>the termux-am socket server ({@link TermuxAmSocketServer#updateState(Context)}), which is
 *     started/stopped to match the property and also updates the value exported to new shell
 *     sessions and tasks via the
 *     {@link com.termux.shared.termux.shell.command.environment.TermuxAppShellEnvironment} env;</li>
 *     <li>the file share/view receiver entry components
 *     ({@link FileReceiverActivity#updateFileReceiverActivityComponentsState(Context)}).</li>
 * </ul>
 *
 * Already running shell sessions and tasks keep the environment snapshot they captured when they
 * were created, since a running process's environment cannot be mutated; the reload only affects the
 * components themselves and subsequent new sessions. Terminal/activity styling is reloaded separately
 * by {@link com.termux.app.TermuxActivity}.
 *
 * This entry point is process-global and does not require a visible activity, so it can be invoked
 * from the activity reload path or, in the future, from a background path.
 */
public final class TermuxAppReloadUtils {

    private static final String LOG_TAG = "TermuxAppReloadUtils";

    private TermuxAppReloadUtils() {}

    /**
     * Reload Termux app settings and reconcile the socket server and file receiver components with
     * the current property values.
     *
     * @param context The {@link Context} for the components being updated.
     * @param reloadProperties Whether the {@link TermuxAppSharedProperties} in-memory cache should be
     *                         reloaded from disk first. Pass {@code false} if the caller has already
     *                         called {@link TermuxAppSharedProperties#loadTermuxPropertiesFromDisk()}
     *                         to avoid reading the file twice.
     */
    public static void reloadTermuxAppSettings(@NonNull Context context, boolean reloadProperties) {
        Logger.logDebug(LOG_TAG, "Reloading Termux app settings (reloadProperties=" + reloadProperties + ")");

        if (reloadProperties) {
            TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
            if (properties != null)
                properties.loadTermuxPropertiesFromDisk();
        }

        // Start/stop the termux-am socket server and update the value exported to new sessions/tasks.
        TermuxAmSocketServer.updateState(context);

        // Enable/disable the file share and file view receiver entry components.
        FileReceiverActivity.updateFileReceiverActivityComponentsState(context);
    }

}
