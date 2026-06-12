package com.termux.app.installer.steps;

import com.termux.app.installer.InstallContext;
import com.termux.app.installer.InstallStep;
import com.termux.shared.errors.Error;

/**
 * Creates the prefix directory with the required working-directory permissions so that the staging
 * directory can later be moved into place.
 *
 * <p>Intentionally has no rollback: a step's rollback must never delete the prefix directory.
 */
public final class CreatePrefixDirStep implements InstallStep {

    @Override
    public String getName() {
        return "create-prefix-directory";
    }

    @Override
    public Error execute(InstallContext context) {
        return context.getEnvironment().ensureDirectory("termux prefix directory", context.getPrefixPath());
    }
}
