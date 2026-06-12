package com.termux.app.install.steps;

import android.content.Context;
import android.os.Build;

import com.termux.app.install.InstallContext;
import com.termux.app.install.InstallErrno;
import com.termux.app.install.InstallState;
import com.termux.app.install.InstallStep;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.termux.file.TermuxFileUtils;

/**
 * Validates that the Termux files directory is accessible and that the current
 * user is the primary device user. These are preconditions for all subsequent
 * installation steps.
 */
public class ValidateFilesDirStep implements InstallStep {

    @Override
    public String getName() {
        return "Validate Files Directory";
    }

    @Override
    public InstallState getResultingState() {
        return InstallState.VALIDATING;
    }

    @Override
    public Error execute(InstallContext context) {
        Context androidContext = context.getAndroidContext();

        // Verify primary user on Android N+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
            && !PackageUtils.isCurrentUserThePrimaryUser(androidContext)) {
            return InstallErrno.ERRNO_NOT_PRIMARY_USER.getError();
        }

        // Check files directory accessibility.
        // This also calls Context.getFilesDir(), which ensures the directory is created.
        Error error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(
            androidContext, true, true);
        if (error != null) {
            return InstallErrno.ERRNO_FILES_DIR_INACCESSIBLE.getError(
                Error.getMinimalErrorString(error));
        }

        return null;
    }
}
