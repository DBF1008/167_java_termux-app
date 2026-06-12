package com.termux.app.install.steps;

import android.system.Os;

import com.termux.app.install.InstallContext;
import com.termux.app.install.InstallErrno;
import com.termux.app.install.InstallState;
import com.termux.app.install.InstallStep;
import com.termux.shared.errors.Error;

/**
 * Creates bootstrap symlinks from the parsed {@code SYMLINKS.txt} entries.
 * <p>
 * Each entry in {@link InstallContext#getParsedSymlinks()} is a {@code String[2]}:
 * <ul>
 *   <li>{@code [0]}: the symlink target (relative path)</li>
 *   <li>{@code [1]}: the absolute path of the link to create</li>
 * </ul>
 */
public class CreateBootstrapSymlinksStep implements InstallStep {

    @Override
    public String getName() {
        return "Create Bootstrap Symlinks";
    }

    @Override
    public InstallState getResultingState() {
        return InstallState.FINALIZING;
    }

    @Override
    public Error execute(InstallContext context) {
        for (String[] symlink : context.getParsedSymlinks()) {
            String target = symlink[0];
            String linkPath = symlink[1];
            try {
                Os.symlink(target, linkPath);
            } catch (Exception e) {
                return InstallErrno.ERRNO_SYMLINK_CREATION_FAILED.getError(
                    e, target, linkPath);
            }
        }
        return null;
    }
}
