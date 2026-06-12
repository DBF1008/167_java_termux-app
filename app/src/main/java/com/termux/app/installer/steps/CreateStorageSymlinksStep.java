package com.termux.app.installer.steps;

import com.termux.app.installer.InstallContext;
import com.termux.app.installer.InstallEnvironment;
import com.termux.app.installer.InstallStep;
import com.termux.app.installer.StorageSymlink;
import com.termux.shared.errors.Error;

import java.io.File;
import java.util.List;

/**
 * Creates the {@code ~/storage/*} symlinks: the fixed set (shared, downloads, dcim, ...) followed by
 * one {@code external-<i>} per app external files dir and one {@code media-<i>} per app external media
 * dir.
 *
 * <p>Behavior matches the original installer: {@code null} volume slots are skipped while preserving
 * the index in the name, and the first symlink failure aborts the remaining creations.
 */
public final class CreateStorageSymlinksStep implements InstallStep {

    @Override
    public String getName() {
        return "create-storage-symlinks";
    }

    @Override
    public Error execute(InstallContext context) {
        final InstallEnvironment environment = context.getEnvironment();
        final File storageDir = new File(context.getStorageHomePath());

        List<StorageSymlink> standardSymlinks = environment.getStandardStorageSymlinks();
        for (StorageSymlink symlink : standardSymlinks) {
            Error error = environment.createSymlink(symlink.target.getAbsolutePath(),
                new File(storageDir, symlink.name).getAbsolutePath());
            if (error != null) return error;
        }

        Error error = createIndexedSymlinks(environment, storageDir, environment.getExternalFilesDirs(), "external-");
        if (error != null) return error;

        error = createIndexedSymlinks(environment, storageDir, environment.getExternalMediaDirs(), "media-");
        if (error != null) return error;

        return null;
    }

    private static Error createIndexedSymlinks(InstallEnvironment environment, File storageDir, File[] dirs, String namePrefix) {
        if (dirs == null) return null;
        for (int i = 0; i < dirs.length; i++) {
            File dir = dirs[i];
            if (dir == null) continue; // Skip missing volume slots; the index is preserved in the name.
            Error error = environment.createSymlink(dir.getAbsolutePath(),
                new File(storageDir, namePrefix + i).getAbsolutePath());
            if (error != null) return error;
        }
        return null;
    }
}
