package com.termux.app.installer;

import android.content.Context;

import com.termux.shared.errors.Error;

import java.io.File;
import java.util.List;

/**
 * The platform facade the install/storage steps depend on. It is the single seam that isolates the
 * transaction layer from non-deterministic and device-specific behavior, so the steps can be driven
 * deterministically in JVM tests.
 *
 * <p>It deliberately also abstracts the filesystem mutations (delete/ensure/clear/create directory):
 * the production {@link AndroidInstallEnvironment} delegates those straight to
 * {@link com.termux.shared.file.FileUtils} (preserving on-device behavior), whereas a test double
 * backs them with plain {@code java.io} on a temporary directory — avoiding the
 * {@code android.system.Os}-based file-type probing that {@code FileUtils} performs and that does not
 * work off-device.
 *
 * <p>Filesystem operations follow the {@link Error} convention ({@code null} == success). Pure
 * file-content writes during extraction use {@code java.io} directly in the step and are not part of
 * this interface.
 */
public interface InstallEnvironment {

    // --- Filesystem operations ---

    /** Recursively delete the directory (or any file) at {@code path}; missing path is success. */
    Error deleteDirectory(String label, String path);

    /** Create the directory at {@code path} if missing and ensure it has working-directory permissions. */
    Error ensureDirectory(String label, String path);

    /** Empty the directory at {@code path}, creating it if it does not exist. */
    Error clearDirectory(String label, String path);

    /** Create the directory at {@code path} including any missing parent directories. */
    Error createDirectory(String path);

    // --- Native / non-deterministic operations ---

    /** Load the embedded bootstrap zip bytes. */
    byte[] loadBootstrapZip() throws Exception;

    /** Create a symlink at {@code linkPath} pointing to {@code targetPath}. */
    Error createSymlink(String targetPath, String linkPath);

    /** Atomically move {@code source} to {@code destination}; returns {@code false} on failure. */
    boolean renameDirectory(File source, File destination);

    /** Make the file at {@code path} executable. */
    Error chmodExecutable(String path);

    /** (Re)write the Termux environment file. Implementations treat failures as non-fatal. */
    void writeEnvironmentFile(Context androidContext);

    // --- Storage volume providers ---

    /** The fixed {@code ~/storage/*} symlinks (shared, downloads, dcim, ...) for this device/SDK. */
    List<StorageSymlink> getStandardStorageSymlinks();

    /** Per-volume app external files dirs ({@code Android/data/<pkg>}); may contain {@code null} slots. */
    File[] getExternalFilesDirs();

    /** Per-volume app external media dirs ({@code Android/media/<pkg>}); may contain {@code null} slots. */
    File[] getExternalMediaDirs();
}
