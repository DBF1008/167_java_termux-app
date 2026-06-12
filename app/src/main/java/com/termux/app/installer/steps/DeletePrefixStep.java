package com.termux.app.installer.steps;

import com.termux.app.installer.InstallContext;
import com.termux.app.installer.InstallStep;
import com.termux.shared.errors.Error;

/** Deletes the prefix directory (or any file at its destination) so it can be rebuilt from scratch. */
public final class DeletePrefixStep implements InstallStep {

    @Override
    public String getName() {
        return "delete-prefix-directory";
    }

    @Override
    public Error execute(InstallContext context) {
        return context.getEnvironment().deleteDirectory("termux prefix directory", context.getPrefixPath());
    }
}
