package com.termux.app.installer;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;

import com.termux.app.TermuxInstaller;
import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Production {@link InstallEnvironment} backed by the real device APIs. Filesystem operations
 * delegate to {@link FileUtils} (so on-device behavior is identical to the previous implementation),
 * symlink/permission operations use {@link Os}, the bootstrap zip comes from the embedded native
 * library via {@link TermuxInstaller#loadZipBytes()}, and storage volumes come from
 * {@link Environment} and the app {@link Context}.
 */
public final class AndroidInstallEnvironment implements InstallEnvironment {

    private final Context context;

    public AndroidInstallEnvironment(Context context) {
        this.context = context;
    }

    // --- Filesystem operations (delegate to FileUtils) ---

    @Override
    public Error deleteDirectory(String label, String path) {
        return FileUtils.deleteFile(label, path, true);
    }

    @Override
    public Error ensureDirectory(String label, String path) {
        // Mirrors TermuxFileUtils.isTermuxPrefix[Staging]DirectoryAccessible(true, true).
        return FileUtils.validateDirectoryFileExistenceAndPermissions(label, path,
            null, true,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS, true, true,
            false, false);
    }

    @Override
    public Error clearDirectory(String label, String path) {
        return FileUtils.clearDirectory(label, path);
    }

    @Override
    public Error createDirectory(String path) {
        return FileUtils.createDirectoryFile(path);
    }

    // --- Native / non-deterministic operations ---

    @Override
    public byte[] loadBootstrapZip() {
        return TermuxInstaller.loadZipBytes();
    }

    @Override
    public Error createSymlink(String targetPath, String linkPath) {
        try {
            Os.symlink(targetPath, linkPath);
            return null;
        } catch (Throwable t) {
            return InstallationErrno.ERRNO_SYMLINK_CREATION_FAILED.getError(t, linkPath, targetPath, String.valueOf(t.getMessage()));
        }
    }

    @Override
    public boolean renameDirectory(File source, File destination) {
        return source.renameTo(destination);
    }

    @Override
    @SuppressWarnings("OctalInteger")
    public Error chmodExecutable(String path) {
        try {
            Os.chmod(path, 0700);
            return null;
        } catch (Throwable t) {
            return InstallationErrno.ERRNO_CHMOD_FAILED.getError(t, path, String.valueOf(t.getMessage()));
        }
    }

    @Override
    public void writeEnvironmentFile(Context androidContext) {
        // TermuxShellEnvironment.writeEnvironmentToFile already treats write failures as non-fatal
        // (it logs and returns); WriteEnvFileStep additionally guards against any thrown exception.
        TermuxShellEnvironment.writeEnvironmentToFile(androidContext);
    }

    // --- Storage volume providers ---

    @Override
    public List<StorageSymlink> getStandardStorageSymlinks() {
        List<StorageSymlink> symlinks = new ArrayList<>();
        symlinks.add(new StorageSymlink("shared", Environment.getExternalStorageDirectory()));
        symlinks.add(new StorageSymlink("documents", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)));
        symlinks.add(new StorageSymlink("downloads", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)));
        symlinks.add(new StorageSymlink("dcim", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)));
        symlinks.add(new StorageSymlink("pictures", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)));
        symlinks.add(new StorageSymlink("music", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)));
        symlinks.add(new StorageSymlink("movies", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)));
        symlinks.add(new StorageSymlink("podcasts", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            symlinks.add(new StorageSymlink("audiobooks", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS)));
        }
        return symlinks;
    }

    @Override
    public File[] getExternalFilesDirs() {
        return context.getExternalFilesDirs(null);
    }

    @Override
    public File[] getExternalMediaDirs() {
        return context.getExternalMediaDirs();
    }
}
