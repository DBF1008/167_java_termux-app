package com.termux.app.installer.steps;

import com.termux.app.installer.InstallContext;
import com.termux.app.installer.InstallStep;
import com.termux.shared.errors.Error;

/**
 * Creates the prefix staging directory with the required working-directory permissions.
 *
 * <p>Its rollback deletes the staging tree so a failed run leaves a clean slate for the next attempt.
 * It never touches the prefix directory.
 */
public final class CreateStagingDirStep implements InstallStep {

    @Override
    public String getName() {
        return "create-staging-directory";
    }

    @Override
    public Error execute(InstallContext context) {
        return context.getEnvironment().ensureDirectory("termux prefix staging directory", context.getStagingPath());
    }

    @Override
    public void rollback(InstallContext context) {
        context.getEnvironment().deleteDirectory("termux prefix staging directory", context.getStagingPath());
    }
}
