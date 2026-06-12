package com.termux.app.install.steps;

import com.termux.app.install.InstallContext;
import com.termux.app.install.InstallErrno;
import com.termux.app.install.InstallState;
import com.termux.app.install.InstallStep;
import com.termux.shared.errors.Error;
import com.termux.shared.termux.file.TermuxFileUtils;

/**
 * Creates the staging and prefix directories with required permissions.
 * <p>
 * This step is idempotent: if directories already exist with correct
 * permissions, this is a no-op.
 */
public class CreateDirectoriesStep implements InstallStep {

    @Override
    public String getName() {
        return "Create Directories";
    }

    @Override
    public InstallState getResultingState() {
        return InstallState.PREPARING;
    }

    @Override
    public Error execute(InstallContext context) {
        // Create staging directory with required permissions
        Error error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
        if (error != null) {
            return InstallErrno.ERRNO_CREATE_DIR_FAILED.getError(
                Error.getErrorMarkdownString(error));
        }

        // Create prefix directory with required permissions
        error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
        if (error != null) {
            return InstallErrno.ERRNO_CREATE_DIR_FAILED.getError(
                Error.getErrorMarkdownString(error));
        }

        return null;
    }
}
