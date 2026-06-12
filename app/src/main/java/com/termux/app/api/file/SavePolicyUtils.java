package com.termux.app.api.file;

import java.io.File;

/**
 * Pure static utility for file save policy logic. Has no Android dependencies
 * so it can be unit tested with plain JUnit (no Robolectric needed).
 */
public final class SavePolicyUtils {

    /** Save policy: show dialog, apply safe naming to prevent overwrites. */
    public static final String POLICY_PROMPT = "prompt";

    /** Save policy: silently save with auto-renaming, then open directory. */
    public static final String POLICY_AUTO_RENAME = "auto-rename";

    /** Save policy: show dialog, allow overwriting existing files (legacy behaviour). */
    public static final String POLICY_OVERWRITE = "overwrite";

    private SavePolicyUtils() {} // prevent instantiation

    /**
     * Given a directory and a desired filename, returns a non-conflicting filename.
     * <p>
     * If no file exists at {@code dir/filename}, returns {@code filename} unchanged.
     * Otherwise appends {@code (1)}, {@code (2)}, … before the last dot (extension separator)
     * until a free name is found.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code getSafeFileName(dir, "photo.jpg")}  → {@code "photo.jpg"}      (no conflict)</li>
     *   <li>{@code getSafeFileName(dir, "photo.jpg")}  → {@code "photo(1).jpg"}   (photo.jpg exists)</li>
     *   <li>{@code getSafeFileName(dir, "archive")}    → {@code "archive(1)"}     (no extension, archive exists)</li>
     *   <li>{@code getSafeFileName(dir, "tar.gz.bak")} → {@code "tar.gz(1).bak"}  (splits on LAST dot only)</li>
     * </ul>
     *
     * @param dir      Target directory.
     * @param fileName Desired file name (non-null, non-empty).
     * @return A filename that does not conflict with existing files in dir.
     * @throws IllegalArgumentException if fileName is null or empty.
     */
    public static String getSafeFileName(File dir, String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("fileName must not be null or empty");
        }

        File candidate = new File(dir, fileName);
        if (!candidate.exists()) {
            return fileName;
        }

        // Split on the LAST dot to preserve compound extensions like .tar.gz
        int lastDot = fileName.lastIndexOf('.');
        String baseName;
        String extension;
        if (lastDot > 0) {
            baseName = fileName.substring(0, lastDot);
            extension = fileName.substring(lastDot); // includes the dot
        } else {
            // No extension, or dot is at position 0 (hidden file like .gitignore)
            baseName = fileName;
            extension = "";
        }

        int suffix = 1;
        while (true) {
            String safeName = baseName + "(" + suffix + ")" + extension;
            if (!new File(dir, safeName).exists()) {
                return safeName;
            }
            suffix++;
            if (suffix > 99999) {
                throw new IllegalStateException("Could not find unique name after 99999 attempts");
            }
        }
    }
}
