package com.termux.shared.termux.settings.reload;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.am.TermuxAmSocketServer;

/**
 * Centralised coordinator for safely reloading Termux settings at runtime.
 * <p>
 * A typical reload cycle:
 * <ol>
 *   <li>Re-read {@code termux.properties} from disk.</li>
 *   <li>Invoke {@link ReloadCallback#onPropertiesReloaded()} so the caller can refresh
 *       UI elements and file-sharing component state.</li>
 *   <li>Update the termux-am socket server (start/stop) according to the fresh property value.</li>
 *   <li>Invoke {@link ReloadCallback#onReloadComplete(ReloadResult)} with a summary of what changed.</li>
 * </ol>
 * <p>
 * Thread safety: this class is safe to call from any thread. Concurrent {@link #reload} calls are
 * serialised via synchronisation. Exceptions thrown inside callbacks do not abort the remaining
 * reload steps.
 */
public class TermuxSettingsReloader {

    private static final String LOG_TAG = "TermuxSettingsReloader";

    // -------------------------------------------------------------------------
    // Result
    // -------------------------------------------------------------------------

    /** Immutable result of a reload operation. */
    public static class ReloadResult {

        /** Whether the reload completed without fatal errors. */
        public final boolean success;

        /** {@code true} if the AM socket server was started or stopped during this reload. */
        public final boolean amSocketServerStateChanged;

        /**
         * {@code true} if the AM socket server state changed, meaning existing shell sessions
         * still hold a stale {@code TERMUX_APP_AM_SOCKET_SERVER_ENABLED} environment variable.
         * Only newly created sessions will see the updated value.
         */
        public final boolean amSocketServerEnvStale;

        /** Non-null error description when {@link #success} is {@code false}. */
        @Nullable
        public final String errorMessage;

        private ReloadResult(boolean success, boolean amSocketServerStateChanged,
                             boolean amSocketServerEnvStale, @Nullable String errorMessage) {
            this.success = success;
            this.amSocketServerStateChanged = amSocketServerStateChanged;
            this.amSocketServerEnvStale = amSocketServerEnvStale;
            this.errorMessage = errorMessage;
        }

        /** Create a successful result. */
        public static ReloadResult success(boolean amSocketServerStateChanged,
                                           boolean amSocketServerEnvStale) {
            return new ReloadResult(true, amSocketServerStateChanged, amSocketServerEnvStale, null);
        }

        /** Create a failure result. */
        public static ReloadResult failure(@NonNull String errorMessage) {
            return new ReloadResult(false, false, false, errorMessage);
        }
    }

    // -------------------------------------------------------------------------
    // Callback
    // -------------------------------------------------------------------------

    /** Callback interface for the UI layer to hook into the reload cycle. */
    public interface ReloadCallback {

        /**
         * Called immediately after properties have been reloaded from disk.
         * <p>
         * The caller should refresh any UI elements and component states that depend on property
         * values (e.g. file-sharing receivers, extra keys, margins, night mode).
         */
        void onPropertiesReloaded();

        /**
         * Called after all reload steps (including AM socket server update) have completed.
         * Always called, even if intermediate steps logged non-fatal errors.
         *
         * @param result summary of what changed during this reload
         */
        void onReloadComplete(@NonNull ReloadResult result);
    }

    // -------------------------------------------------------------------------
    // Reload
    // -------------------------------------------------------------------------

    /**
     * Perform a full, coordinated settings reload.
     * <p>
     * This method is safe to call from any thread and from any entry point (Activity, Service,
     * BroadcastReceiver). Concurrent calls are serialised.
     *
     * @param context  application or activity context
     * @param callback optional callback for UI updates; may be {@code null}
     * @return a {@link ReloadResult} describing what happened
     */
    @NonNull
    public static synchronized ReloadResult reload(@NonNull Context context,
                                                   @Nullable ReloadCallback callback) {
        Logger.logInfo(LOG_TAG, "Starting settings reload");

        // -- Step 1: Reload properties from disk --------------------------------
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        if (properties == null) {
            String error = "TermuxAppSharedProperties singleton is null – was init() called?";
            Logger.logError(LOG_TAG, error);
            return notifyFailure(callback, error);
        }

        try {
            properties.loadTermuxPropertiesFromDisk();
            Logger.logInfo(LOG_TAG, "Properties reloaded from disk");
        } catch (Exception e) {
            String error = "Failed to load properties from disk: " + e.getMessage();
            Logger.logError(LOG_TAG, error, e);
            return notifyFailure(callback, error);
        }

        // -- Step 2: Let the caller react to fresh properties -------------------
        if (callback != null) {
            try {
                callback.onPropertiesReloaded();
            } catch (Exception e) {
                // Non-fatal – continue with remaining steps.
                Logger.logError(LOG_TAG, "Callback.onPropertiesReloaded() threw an exception", e);
            }
        }

        // -- Step 3: Update AM Socket Server ------------------------------------
        boolean amStateChanged = false;
        boolean amEnvStale = false;
        try {
            boolean wasRunning = TermuxAmSocketServer.isServerRunning();
            TermuxAmSocketServer.updateState(context);
            boolean nowRunning = TermuxAmSocketServer.isServerRunning();

            amStateChanged = (wasRunning != nowRunning);
            if (amStateChanged) {
                amEnvStale = true;
                Logger.logWarn(LOG_TAG,
                    "AM Socket Server state changed (was=" + wasRunning + ", now=" + nowRunning + "). "
                    + "Existing shell sessions retain stale TERMUX_APP_AM_SOCKET_SERVER_ENABLED env.");
            } else {
                Logger.logDebug(LOG_TAG, "AM Socket Server state unchanged: " + nowRunning);
            }
        } catch (Exception e) {
            // Non-fatal – the rest of the reload is still valid.
            Logger.logError(LOG_TAG, "AM Socket Server update failed", e);
        }

        // -- Step 4: Complete ---------------------------------------------------
        ReloadResult result = ReloadResult.success(amStateChanged, amEnvStale);
        Logger.logInfo(LOG_TAG, "Settings reload complete: amStateChanged=" + amStateChanged
            + ", amEnvStale=" + amEnvStale);

        if (callback != null) {
            try {
                callback.onReloadComplete(result);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Callback.onReloadComplete() threw an exception", e);
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @NonNull
    private static ReloadResult notifyFailure(@Nullable ReloadCallback callback,
                                              @NonNull String error) {
        ReloadResult result = ReloadResult.failure(error);
        if (callback != null) {
            try {
                callback.onReloadComplete(result);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failure callback threw exception", e);
            }
        }
        return result;
    }
}
