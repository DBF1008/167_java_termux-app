package com.termux.app.install;

import android.content.Context;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable context object passed to every {@link InstallStep}.
 * <p>
 * Encapsulates all paths and Android context so steps don't reach for static
 * constants directly. This enables testing with custom paths pointing at
 * temp directories.
 * <p>
 * Also carries mutable shared state between steps (e.g., parsed symlinks
 * from SYMLINKS.txt that {@code ExtractBootstrapStep} produces and
 * {@code CreateBootstrapSymlinksStep} consumes).
 */
public class InstallContext {

    private final Context androidContext;
    private final String filesDirPath;
    private final String prefixDirPath;
    private final File prefixDir;
    private final String stagingDirPath;
    private final File stagingDir;
    private final String storageHomeDirPath;
    private final File storageHomeDir;
    private final String homeDirPath;

    // Mutable state shared between steps
    private final List<String[]> parsedSymlinks = new ArrayList<>();

    public InstallContext(Context androidContext,
                          String filesDirPath,
                          String prefixDirPath,
                          File prefixDir,
                          String stagingDirPath,
                          File stagingDir,
                          String storageHomeDirPath,
                          File storageHomeDir,
                          String homeDirPath) {
        this.androidContext = androidContext;
        this.filesDirPath = filesDirPath;
        this.prefixDirPath = prefixDirPath;
        this.prefixDir = prefixDir;
        this.stagingDirPath = stagingDirPath;
        this.stagingDir = stagingDir;
        this.storageHomeDirPath = storageHomeDirPath;
        this.storageHomeDir = storageHomeDir;
        this.homeDirPath = homeDirPath;
    }

    /**
     * Creates an {@link InstallContext} using standard {@link TermuxConstants} paths.
     */
    public static InstallContext createDefault(Context context) {
        return new InstallContext(
            context,
            TermuxConstants.TERMUX_FILES_DIR_PATH,
            TermuxConstants.TERMUX_PREFIX_DIR_PATH,
            TermuxConstants.TERMUX_PREFIX_DIR,
            TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH,
            TermuxConstants.TERMUX_STAGING_PREFIX_DIR,
            TermuxConstants.TERMUX_STORAGE_HOME_DIR_PATH,
            TermuxConstants.TERMUX_STORAGE_HOME_DIR,
            TermuxConstants.TERMUX_HOME_DIR_PATH
        );
    }

    public Context getAndroidContext() {
        return androidContext;
    }

    public String getFilesDirPath() {
        return filesDirPath;
    }

    public String getPrefixDirPath() {
        return prefixDirPath;
    }

    public File getPrefixDir() {
        return prefixDir;
    }

    public String getStagingDirPath() {
        return stagingDirPath;
    }

    public File getStagingDir() {
        return stagingDir;
    }

    public String getStorageHomeDirPath() {
        return storageHomeDirPath;
    }

    public File getStorageHomeDir() {
        return storageHomeDir;
    }

    public String getHomeDirPath() {
        return homeDirPath;
    }

    /**
     * Returns the mutable list of parsed symlinks shared between steps.
     * Each entry is a {@code String[2]} where {@code [0]} is the symlink target
     * and {@code [1]} is the absolute link path.
     */
    public List<String[]> getParsedSymlinks() {
        return parsedSymlinks;
    }
}
