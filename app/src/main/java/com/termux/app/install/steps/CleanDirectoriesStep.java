package com.termux.app.install.steps;

import com.termux.app.install.InstallContext;
import com.termux.app.install.InstallErrno;
import com.termux.app.install.InstallState;
import com.termux.app.install.InstallStep;
import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;

/**
 * Deletes the staging and prefix directories (or any file at their destination)
 * to ensure a clean slate before installation.
 * <p>
 * This step is idempotent: deleting a non-existent path with
 * {@code ignoreNonExistentFile=true} is a no-op.
 */
public class CleanDirectoriesStep implements InstallStep {

    @Override
    public String getName() {
        return "Clean Directories";
    }

    @Override
    public InstallState getResultingState() {
        return InstallState.PREPARING;
    }

    @Override
    public Error execute(InstallContext context) {
        // Delete staging directory or any file at its destination
        Error error = FileUtils.deleteFile(
            "termux prefix staging directory",
            context.getStagingDirPath(),
            true);
        if (error != null) {
            return InstallErrno.ERRNO_CLEAN_FAILED.getError(
                Error.getErrorMarkdownString(error));
        }

        // Delete prefix directory or any file at its destination
        error = FileUtils.deleteFile(
            "termux prefix directory",
            context.getPrefixDirPath(),
            true);
        if (error != null) {
            return InstallErrno.ERRNO_CLEAN_FAILED.getError(
                Error.getErrorMarkdownString(error));
        }

        return null;
    }
}
