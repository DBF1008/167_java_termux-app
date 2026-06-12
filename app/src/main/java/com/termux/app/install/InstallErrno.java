package com.termux.app.install;

import com.termux.shared.errors.Errno;

/**
 * Error codes for the installation coordinator and its steps.
 * <p>
 * Follows the same pattern as {@link com.termux.shared.file.FileUtilsErrno}:
 * extends {@link Errno} and defines static {@link Errno} instances with
 * a shared TYPE and a dedicated code range (1001-1015).
 */
public class InstallErrno extends Errno {

    public static final String TYPE = "InstallError";


    /* Pre-condition check errors (1001-1005) */

    public static final Errno ERRNO_FILES_DIR_INACCESSIBLE =
        new Errno(TYPE, 1001, "Termux files directory is not accessible: %s");

    public static final Errno ERRNO_NOT_PRIMARY_USER =
        new Errno(TYPE, 1002, "Termux can only be run as the primary device user.");

    public static final Errno ERRNO_PREFIX_CHECK_FAILED =
        new Errno(TYPE, 1003, "Failed to check termux prefix directory: %s");


    /* Directory preparation errors (1004-1006) */

    public static final Errno ERRNO_CLEAN_FAILED =
        new Errno(TYPE, 1004, "Failed to clean directory: %s");

    public static final Errno ERRNO_CREATE_DIR_FAILED =
        new Errno(TYPE, 1005, "Failed to create directory: %s");

    public static final Errno ERRNO_ENSURE_DIR_FAILED =
        new Errno(TYPE, 1006, "Failed to ensure directory exists: %s");


    /* Extraction errors (1007-1010) */

    public static final Errno ERRNO_ZIP_LOAD_FAILED =
        new Errno(TYPE, 1007, "Failed to load bootstrap zip bytes: %s");

    public static final Errno ERRNO_ZIP_EXTRACT_FAILED =
        new Errno(TYPE, 1008, "Failed to extract bootstrap zip: %s");

    public static final Errno ERRNO_MALFORMED_SYMLINKS_TXT =
        new Errno(TYPE, 1009, "Malformed SYMLINKS.txt line: %s");

    public static final Errno ERRNO_NO_SYMLINKS_TXT =
        new Errno(TYPE, 1010, "No SYMLINKS.txt found in bootstrap zip.");


    /* Finalization errors (1011-1013) */

    public static final Errno ERRNO_SYMLINK_CREATION_FAILED =
        new Errno(TYPE, 1011, "Failed to create bootstrap symlink: %s -> %s");

    public static final Errno ERRNO_ATOMIC_RENAME_FAILED =
        new Errno(TYPE, 1012, "Moving termux prefix staging to prefix directory failed. Staging: %s, Prefix: %s");

    public static final Errno ERRNO_ENV_WRITE_FAILED =
        new Errno(TYPE, 1013, "Failed to write environment file: %s");


    /* Storage errors (1014-1015) */

    public static final Errno ERRNO_STORAGE_CLEAR_FAILED =
        new Errno(TYPE, 1014, "Failed to clear storage directory: %s");

    public static final Errno ERRNO_STORAGE_SYMLINK_FAILED =
        new Errno(TYPE, 1015, "Failed to create storage symlink for %s: %s");


    /* Coordinator errors (1016-1017) */

    public static final Errno ERRNO_ALREADY_RUNNING =
        new Errno(TYPE, 1016, "Installation already in progress.");

    public static final Errno ERRNO_INVALID_RETRY_STATE =
        new Errno(TYPE, 1017, "Cannot retry: current state is %s, expected FAILED.");


    public InstallErrno(String type, int code, String message) {
        super(type, code, message);
    }
}
