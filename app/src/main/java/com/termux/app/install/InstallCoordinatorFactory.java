package com.termux.app.install;

import android.content.Context;

import com.termux.app.TermuxInstaller;
import com.termux.app.install.steps.AtomicRenameStep;
import com.termux.app.install.steps.CheckPrefixStep;
import com.termux.app.install.steps.CleanDirectoriesStep;
import com.termux.app.install.steps.CreateBootstrapSymlinksStep;
import com.termux.app.install.steps.CreateDirectoriesStep;
import com.termux.app.install.steps.ExtractBootstrapStep;
import com.termux.app.install.steps.SetupStorageSymlinksStep;
import com.termux.app.install.steps.ValidateFilesDirStep;
import com.termux.app.install.steps.WriteEnvironmentStep;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Creates and holds the singleton {@link InstallCoordinator}.
 * Configures the step pipeline with production dependencies.
 */
public final class InstallCoordinatorFactory {

    private static volatile InstallCoordinator sInstance;

    private InstallCoordinatorFactory() {}

    /**
     * Returns the singleton coordinator instance, creating it if necessary.
     */
    public static InstallCoordinator getInstance(Context context) {
        if (sInstance == null) {
            synchronized (InstallCoordinatorFactory.class) {
                if (sInstance == null) {
                    sInstance = create(InstallContext.createDefault(
                        context.getApplicationContext()));
                }
            }
        }
        return sInstance;
    }

    /**
     * Creates a fresh coordinator with the given context.
     * Visible for testing.
     */
    public static InstallCoordinator create(InstallContext context) {
        ExtractBootstrapStep.ZipBytesProvider zipProvider = TermuxInstaller::loadZipBytes;

        List<InstallStep> fullInstallSteps = Arrays.asList(
            new ValidateFilesDirStep(),
            new CheckPrefixStep(),
            new CleanDirectoriesStep(),
            new CreateDirectoriesStep(),
            new ExtractBootstrapStep(zipProvider),
            new CreateBootstrapSymlinksStep(),
            new AtomicRenameStep(),
            new WriteEnvironmentStep(),
            new SetupStorageSymlinksStep()
        );

        List<InstallStep> storageOnlySteps = Collections.singletonList(
            new SetupStorageSymlinksStep()
        );

        return new InstallCoordinator(context, fullInstallSteps, storageOnlySteps);
    }

    /**
     * Creates a coordinator with custom step lists. Visible for testing.
     */
    public static InstallCoordinator createWithSteps(InstallContext context,
                                                     List<InstallStep> fullInstallSteps,
                                                     List<InstallStep> storageOnlySteps) {
        return new InstallCoordinator(context, fullInstallSteps, storageOnlySteps);
    }

    /**
     * Resets the singleton. Visible for testing only.
     */
    static synchronized void resetForTesting() {
        sInstance = null;
    }
}
