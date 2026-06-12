package com.termux.app.installer;

import android.content.Context;

import com.termux.shared.termux.TermuxConstants;

/**
 * Immutable inputs shared by every step of an {@link InstallTransaction}: the Termux directory paths
 * the steps operate on and the {@link InstallEnvironment} platform facade.
 *
 * <p>Steps always read paths from here rather than the static {@code TermuxConstants.TERMUX_*_DIR_PATH}
 * values, which is what lets tests run a transaction against a temporary directory.
 */
public final class InstallContext {

    private final Context androidContext;
    private final String prefixPath;
    private final String stagingPath;
    private final String homePath;
    private final String envFilePath;
    private final String storageHomePath;
    private final InstallEnvironment environment;
    private final String logTag;

    public InstallContext(Context androidContext, String prefixPath, String stagingPath, String homePath,
                          String envFilePath, String storageHomePath, InstallEnvironment environment, String logTag) {
        this.androidContext = androidContext;
        this.prefixPath = prefixPath;
        this.stagingPath = stagingPath;
        this.homePath = homePath;
        this.envFilePath = envFilePath;
        this.storageHomePath = storageHomePath;
        this.environment = environment;
        this.logTag = logTag;
    }

    /** Build a context wired to the real Termux paths from {@link TermuxConstants}. */
    public static InstallContext forTermux(Context androidContext, InstallEnvironment environment) {
        return new InstallContext(
            androidContext,
            TermuxConstants.TERMUX_PREFIX_DIR_PATH,
            TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH,
            TermuxConstants.TERMUX_HOME_DIR_PATH,
            TermuxConstants.TERMUX_ENV_FILE_PATH,
            TermuxConstants.TERMUX_STORAGE_HOME_DIR_PATH,
            environment,
            "TermuxInstaller");
    }

    public Context getAndroidContext() {
        return androidContext;
    }

    public String getPrefixPath() {
        return prefixPath;
    }

    public String getStagingPath() {
        return stagingPath;
    }

    public String getHomePath() {
        return homePath;
    }

    public String getEnvFilePath() {
        return envFilePath;
    }

    public String getStorageHomePath() {
        return storageHomePath;
    }

    public InstallEnvironment getEnvironment() {
        return environment;
    }

    public String getLogTag() {
        return logTag;
    }
}
