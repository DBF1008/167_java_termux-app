package com.termux.shared.notification;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;

/**
 * Shared helper for the simple foreground-service notification used by services that only need to
 * satisfy the foreground-service requirement while they do short-lived work.
 *
 * The plugin {@code RunCommandService} previously kept its own copy of the
 * {@code runStartForeground}/{@code runStopForeground}/{@code buildNotification}/{@code setupNotificationChannel}
 * boilerplate that parallels the real one in {@code TermuxService}. That copy lives here now so the
 * notification setup is not duplicated. The small-icon resource is passed in by the caller since it
 * lives in the app module.
 */
public final class ForegroundServiceNotificationHelper {

    private ForegroundServiceNotificationHelper() {}

    /**
     * Put the {@code service} into the foreground with the
     * {@link TermuxConstants#TERMUX_RUN_COMMAND_NOTIFICATION_ID} notification. No-op below
     * {@link Build.VERSION_CODES#O} (matching the previous behaviour, where the RUN_COMMAND service
     * only ran in the foreground on Android O+).
     *
     * @param service The {@link Service} to put into the foreground.
     * @param smallIconResId The drawable resource id for the notification's small icon.
     */
    public static void startRunCommandForeground(@NonNull Service service, int smallIconResId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationUtils.setupNotificationChannel(service, TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        service.startForeground(TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_ID,
            buildRunCommandNotification(service, smallIconResId));
    }

    /**
     * Take the {@code service} out of the foreground. No-op below {@link Build.VERSION_CODES#O}.
     *
     * @param service The {@link Service} to take out of the foreground.
     */
    public static void stopRunCommandForeground(@NonNull Service service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        service.stopForeground(true);
    }

    @Nullable
    private static Notification buildRunCommandNotification(@NonNull Context context, int smallIconResId) {
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(context,
            TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_LOW,
            TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_NAME, null, null,
            null, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null) return null;

        // No need to show a timestamp:
        builder.setShowWhen(false);

        // Set notification icon
        builder.setSmallIcon(smallIconResId);

        // Set background color for small notification icon
        builder.setColor(0xFF607D8B);

        return builder.build();
    }

}
