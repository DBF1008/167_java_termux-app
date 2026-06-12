package com.termux.app.installer.steps;

import com.termux.app.installer.InstallContext;
import com.termux.app.installer.InstallStep;
import com.termux.app.installer.InstallationErrno;
import com.termux.shared.errors.Error;

import java.io.File;

/**
 * The commit step: atomically moves the fully-populated staging directory into the prefix directory.
 *
 * <p>This is the transaction's commit boundary — once it succeeds the install is durable, so steps at
 * or after it are never rolled back. It has no rollback of its own.
 */
public final class CommitStagingStep implements InstallStep {

    @Override
    public String getName() {
        return "commit-staging-to-prefix";
    }

    @Override
    public Error execute(InstallContext context) {
        File staging = new File(context.getStagingPath());
        File prefix = new File(context.getPrefixPath());
        if (!context.getEnvironment().renameDirectory(staging, prefix))
            return InstallationErrno.ERRNO_PREFIX_COMMIT_FAILED.getError(context.getStagingPath(), context.getPrefixPath());
        return null;
    }
}
