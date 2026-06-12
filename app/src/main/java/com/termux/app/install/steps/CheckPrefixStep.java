package com.termux.app.install.steps;

import com.termux.app.install.InstallContext;
import com.termux.app.install.InstallState;
import com.termux.app.install.InstallStep;
import com.termux.app.install.SkippableStep;
import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.file.TermuxFileUtils;

/**
 * Checks whether the prefix directory already exists and is non-empty.
 * <p>
 * If the prefix is valid, this step signals via {@link SkippableStep} that
 * the remaining installation steps should be skipped. This handles the
 * common case where Termux is already installed and functional.
 */
public class CheckPrefixStep implements InstallStep, SkippableStep {

    private volatile boolean skipRemaining = false;

    @Override
    public String getName() {
        return "Check Prefix Existence";
    }

    @Override
    public InstallState getResultingState() {
        return InstallState.VALIDATING;
    }

    @Override
    public Error execute(InstallContext context) {
        String prefixPath = context.getPrefixDirPath();

        // If prefix directory exists and is non-empty (follows symlinks),
        // installation is not needed
        if (FileUtils.directoryFileExists(prefixPath, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                // Prefix exists but empty — need to install
                skipRemaining = false;
            } else {
                // Prefix exists and non-empty — installation complete
                skipRemaining = true;
            }
        } else if (FileUtils.fileExists(prefixPath, false)) {
            // Some non-directory file exists at prefix path — need to install
            // (will be cleaned by CleanDirectoriesStep)
            skipRemaining = false;
        } else {
            // Prefix doesn't exist — need to install
            skipRemaining = false;
        }

        return null;
    }

    @Override
    public boolean shouldSkipRemaining() {
        return skipRemaining;
    }
}
