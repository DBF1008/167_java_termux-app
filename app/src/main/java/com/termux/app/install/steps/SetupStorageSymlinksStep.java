package com.termux.app.install.steps;

import android.os.Build;
import android.os.Environment;
import android.system.Os;

import com.termux.app.install.InstallContext;
import com.termux.app.install.InstallErrno;
import com.termux.app.install.InstallState;
import com.termux.app.install.InstallStep;
import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;

import java.io.File;

/**
 * Sets up storage symlinks under {@code ~/storage/} pointing to Android
 * public directories and app-specific external directories.
 * <p>
 * Creates:
 * <ul>
 *   <li>Fixed symlinks: shared, documents, downloads, dcim, pictures, music,
 *       movies, podcasts, audiobooks (API 29+)</li>
 *   <li>Dynamic symlinks: external-N (getExternalFilesDirs) and media-N
 *       (getExternalMediaDirs) for multi-storage support</li>
 * </ul>
 * <p>
 * This step is idempotent: it clears the storage directory first.
 */
public class SetupStorageSymlinksStep implements InstallStep {

    private static final String LOG_TAG = "SetupStorageSymlinksStep";

    @Override
    public String getName() {
        return "Setup Storage Symlinks";
    }

    @Override
    public InstallState getResultingState() {
        return InstallState.POST_INSTALL;
    }

    @Override
    public Error execute(InstallContext context) {
        File storageDir = context.getStorageHomeDir();

        // Clear existing symlinks for idempotent re-execution
        Error error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
        if (error != null) {
            return InstallErrno.ERRNO_STORAGE_CLEAR_FAILED.getError(
                Error.getErrorMarkdownString(error));
        }

        try {
            // Fixed symlinks to Android public directories
            createSymlink(storageDir, "shared",
                Environment.getExternalStorageDirectory());
            createSymlink(storageDir, "documents",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
            createSymlink(storageDir, "downloads",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
            createSymlink(storageDir, "dcim",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM));
            createSymlink(storageDir, "pictures",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
            createSymlink(storageDir, "music",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC));
            createSymlink(storageDir, "movies",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES));
            createSymlink(storageDir, "podcasts",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                createSymlink(storageDir, "audiobooks",
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS));
            }

            // Dynamic symlinks for external files directories (multi-storage)
            android.content.Context androidContext = context.getAndroidContext();
            if (androidContext != null) {
                createExternalSymlinks(androidContext, storageDir, "external",
                    androidContext.getExternalFilesDirs(null));
                createExternalSymlinks(androidContext, storageDir, "media",
                    androidContext.getExternalMediaDirs());
            }

        } catch (Exception e) {
            return InstallErrno.ERRNO_STORAGE_SYMLINK_FAILED.getError(
                e, "storage setup", e.getMessage());
        }

        return null;
    }

    private void createSymlink(File storageDir, String name, File target) throws Exception {
        Logger.logInfo(LOG_TAG, "Creating symlink ~/storage/" + name
            + " -> " + target.getAbsolutePath());
        Os.symlink(target.getAbsolutePath(),
            new File(storageDir, name).getAbsolutePath());
    }

    private void createExternalSymlinks(android.content.Context context, File storageDir,
                                        String prefix, File[] dirs) throws Exception {
        if (dirs == null || dirs.length == 0) return;

        for (int i = 0; i < dirs.length; i++) {
            File dir = dirs[i];
            if (dir == null) continue;
            String symlinkName = prefix + "-" + i;
            Logger.logInfo(LOG_TAG, "Creating symlink ~/storage/" + symlinkName
                + " -> " + dir.getAbsolutePath());
            Os.symlink(dir.getAbsolutePath(),
                new File(storageDir, symlinkName).getAbsolutePath());
        }
    }
}
