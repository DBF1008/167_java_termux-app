package com.termux.app.installer;

import android.content.Context;

import com.termux.shared.errors.Error;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A test {@link InstallEnvironment} backed by plain {@code java.io} on the host filesystem.
 *
 * <p>Filesystem operations act for real on a temporary directory (so steps can be observed end to
 * end), while symlink creation is <em>recorded</em> rather than performed (dodging host
 * symlink-permission flakiness and letting tests assert on the requested links). The bootstrap zip,
 * rename outcome, symlink failures and storage volumes are all injectable, which makes the
 * extraction-failure / rename-failure / retry / multi-storage scenarios deterministic without any
 * dependency on {@code android.system.Os}.
 */
public final class LocalInstallEnvironment implements InstallEnvironment {

    /** Bytes returned by {@link #loadBootstrapZip()} (when {@link #zipLoadException} is {@code null}). */
    public byte[] zipBytes = new byte[0];
    /** If set, {@link #loadBootstrapZip()} throws this instead of returning bytes. */
    public RuntimeException zipLoadException;
    /** Whether {@link #renameDirectory(File, File)} is allowed to succeed. */
    public boolean renameSucceeds = true;
    /** Set to {@code true} once {@link #writeEnvironmentFile(Context)} is invoked. */
    public boolean envWritten = false;
    /** Every requested symlink as {@code {targetPath, linkPath}}, in order. */
    public final List<String[]> createdSymlinks = new ArrayList<>();
    /** If a link path ends with this suffix, {@link #createSymlink} returns an error (and records it). */
    public String failSymlinkLinkSuffix;
    /** The fixed storage symlinks returned by {@link #getStandardStorageSymlinks()}. */
    public List<StorageSymlink> standardStorageSymlinks = new ArrayList<>();
    /** The per-volume external files dirs (may contain {@code null} slots). */
    public File[] externalFilesDirs = new File[0];
    /** The per-volume external media dirs (may contain {@code null} slots). */
    public File[] externalMediaDirs = new File[0];

    // --- Filesystem operations (java.io, host-safe) ---

    @Override
    public Error deleteDirectory(String label, String path) {
        deleteRecursively(new File(path));
        return null;
    }

    @Override
    public Error ensureDirectory(String label, String path) {
        return mkdirs(path);
    }

    @Override
    public Error clearDirectory(String label, String path) {
        File dir = new File(path);
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null)
                for (File child : children)
                    deleteRecursively(child);
            return null;
        }
        return mkdirs(path);
    }

    @Override
    public Error createDirectory(String path) {
        return mkdirs(path);
    }

    // --- Native / non-deterministic operations ---

    @Override
    public byte[] loadBootstrapZip() {
        if (zipLoadException != null)
            throw zipLoadException;
        return zipBytes;
    }

    @Override
    public Error createSymlink(String targetPath, String linkPath) {
        createdSymlinks.add(new String[]{targetPath, linkPath});
        if (failSymlinkLinkSuffix != null && linkPath.endsWith(failSymlinkLinkSuffix))
            return InstallationErrno.ERRNO_SYMLINK_CREATION_FAILED.getError(linkPath, targetPath, "forced test failure");
        return null;
    }

    @Override
    public boolean renameDirectory(File source, File destination) {
        if (!renameSucceeds)
            return false;
        // The transaction creates the prefix dir before committing, so the rename target exists as an
        // empty directory. Host File.renameTo onto an existing dir is unreliable across OSes (it works
        // via rename(2) on-device); remove the empty target first so the move is deterministic in tests.
        if (destination.isDirectory()) {
            String[] children = destination.list();
            if (children == null || children.length == 0)
                destination.delete();
        }
        return source.renameTo(destination);
    }

    @Override
    public Error chmodExecutable(String path) {
        return null;
    }

    @Override
    public void writeEnvironmentFile(Context androidContext) {
        envWritten = true;
    }

    // --- Storage volume providers ---

    @Override
    public List<StorageSymlink> getStandardStorageSymlinks() {
        return standardStorageSymlinks;
    }

    @Override
    public File[] getExternalFilesDirs() {
        return externalFilesDirs;
    }

    @Override
    public File[] getExternalMediaDirs() {
        return externalMediaDirs;
    }

    // --- Test helpers ---

    /** The link names (last path segment) of every requested symlink, in order. */
    public List<String> recordedLinkNames() {
        List<String> names = new ArrayList<>();
        for (String[] symlink : createdSymlinks)
            names.add(new File(symlink[1]).getName());
        return names;
    }

    private static Error mkdirs(String path) {
        File dir = new File(path);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())
            return InstallationErrno.ERRNO_STEP_EXECUTION_FAILED.getError("create-directory", "mkdirs failed for " + path);
        return null;
    }

    private static void deleteRecursively(File file) {
        if (file == null) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null)
                for (File child : children)
                    deleteRecursively(child);
        }
        file.delete();
    }
}
