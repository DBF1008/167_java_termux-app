package com.termux.app.installer.steps;

import com.termux.app.installer.InstallContext;
import com.termux.app.installer.InstallStep;
import com.termux.shared.errors.Error;

/** Deletes the prefix staging directory (or any file at its destination) left over from a prior run. */
public final class DeleteStagingStep implements InstallStep {

    @Override
    public String getName() {
        return "delete-staging-directory";
    }

    @Override
    public Error execute(InstallContext context) {
        return context.getEnvironment().deleteDirectory("termux prefix staging directory", context.getStagingPath());
    }
}
