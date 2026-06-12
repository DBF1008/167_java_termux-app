package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.view.WindowManager;

import com.termux.R;
import com.termux.app.install.InstallCoordinator;
import com.termux.app.install.InstallCoordinatorFactory;
import com.termux.app.install.InstallState;
import com.termux.app.install.InstallStateListener;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.errors.Error;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;

/**
 * Legacy facade for bootstrap installation and storage symlink setup.
 * <p>
 * <b>Deprecated</b>: This class delegates to {@link InstallCoordinator} for
 * all actual work. New callers should use {@link InstallCoordinatorFactory} directly.
 * <p>
 * This class is preserved for:
 * <ul>
 *   <li>Backward compatibility with existing call sites</li>
 *   <li>Holding the native {@link #getZip()} method binding</li>
 *   <li>Providing the error dialog UI pattern</li>
 * </ul>
 */
public final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /**
     * Performs bootstrap setup if necessary, delegating to {@link InstallCoordinator}.
     *
     * @deprecated Use {@link InstallCoordinatorFactory#getInstance(Context)} and
     *             {@link InstallCoordinator#runFullInstall()} directly.
     */
    @Deprecated
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        InstallCoordinator coordinator = InstallCoordinatorFactory.getInstance(activity);

        final ProgressDialog progress = ProgressDialog.show(activity, null,
            activity.getString(R.string.bootstrap_installer_body), true, false);

        // Register listener for UI updates during installation
        InstallStateListener listener = new InstallStateListener() {
            @Override
            public void onStateChanged(InstallState newState) {
                activity.runOnUiThread(() -> {
                    if (newState.isTerminal()) {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    }
                });
            }
        };
        coordinator.addListener(listener);

        new Thread(() -> {
            Logger.logInfo(LOG_TAG, "Starting bootstrap installation via InstallCoordinator.");

            Error error = coordinator.runFullInstall();

            activity.runOnUiThread(() -> {
                try {
                    progress.dismiss();
                } catch (RuntimeException e) {
                    // Activity already dismissed - ignore.
                }

                if (error == null) {
                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");
                    whenDone.run();
                } else {
                    showBootstrapErrorDialog(activity, whenDone,
                        Error.getErrorMarkdownString(error));
                }
            });
        }).start();
    }

    /**
     * Shows an error dialog with "Abort" and "Try Again" options.
     * "Try Again" uses {@link InstallCoordinator#retryFullInstall()}.
     */
    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity)
                    .setTitle(R.string.bootstrap_error_title)
                    .setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        // Retry via coordinator instead of recursive call
                        retryBootstrap(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    /**
     * Retries bootstrap installation via the coordinator.
     */
    private static void retryBootstrap(Activity activity, Runnable whenDone) {
        InstallCoordinator coordinator = InstallCoordinatorFactory.getInstance(activity);

        final ProgressDialog progress = ProgressDialog.show(activity, null,
            activity.getString(R.string.bootstrap_installer_body), true, false);

        new Thread(() -> {
            Error error = coordinator.retryFullInstall();

            activity.runOnUiThread(() -> {
                try {
                    progress.dismiss();
                } catch (RuntimeException e) {
                    // Activity already dismissed - ignore.
                }

                if (error == null) {
                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully on retry.");
                    whenDone.run();
                } else {
                    showBootstrapErrorDialog(activity, whenDone,
                        Error.getErrorMarkdownString(error));
                }
            });
        }).start();
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    /**
     * Sets up storage symlinks, delegating to {@link InstallCoordinator}.
     *
     * @deprecated Use {@link InstallCoordinatorFactory#getInstance(Context)} and
     *             {@link InstallCoordinator#runStorageSetup()} directly.
     */
    @Deprecated
    static void setupStorageSymlinks(final Context context) {
        final String logTag = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(logTag, "Setting up storage symlinks via InstallCoordinator.");

        InstallCoordinator coordinator = InstallCoordinatorFactory.getInstance(context);

        new Thread(() -> {
            Error error = coordinator.runStorageSetup();
            if (error != null) {
                Logger.logErrorAndShowToast(context, logTag, error.getMessage());
                Logger.logErrorExtended(logTag, "Setup Storage Error\n" + error.toString());
                TermuxCrashUtils.sendCrashReportNotification(context, logTag, title, null,
                    "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                    true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
            }
        }).start();
    }

    /**
     * Loads the bootstrap zip bytes from the native library.
     * Used by {@link InstallCoordinatorFactory} as the {@code ZipBytesProvider}.
     */
    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();
}
