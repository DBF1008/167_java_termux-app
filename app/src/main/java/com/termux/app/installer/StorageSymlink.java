package com.termux.app.installer;

import java.io.File;

/**
 * A single {@code ~/storage/<name>} symlink to create: the link {@link #name} (relative to the
 * storage home directory) and its {@link #target} directory.
 */
public final class StorageSymlink {

    /** The symlink name under the storage home directory, e.g. {@code "downloads"}. */
    public final String name;

    /** The directory the symlink points at. */
    public final File target;

    public StorageSymlink(String name, File target) {
        this.name = name;
        this.target = target;
    }
}
