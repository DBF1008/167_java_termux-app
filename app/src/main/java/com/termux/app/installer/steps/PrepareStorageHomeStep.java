package com.termux.app.installer.steps;

import com.termux.app.installer.InstallContext;
import com.termux.app.installer.InstallStep;
import com.termux.shared.errors.Error;

/** Ensures the {@code ~/storage} directory exists and is emptied before symlinks are (re)created. */
public final class PrepareStorageHomeStep implements InstallStep {

    @Override
    public String getName() {
        return "prepare-storage-home";
    }

    @Override
    public Error execute(InstallContext context) {
        return context.getEnvironment().clearDirectory("~/storage", context.getStorageHomePath());
    }
}
