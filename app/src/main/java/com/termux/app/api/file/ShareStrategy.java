package com.termux.app.api.file;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.regex.Pattern;

/**
 * Save strategy for content received by {@link FileReceiverActivity} (files/text/URLs shared or
 * viewed into Termux from external apps).
 *
 * <p>This centralises the previously hardcoded behaviour of the receiver so that content from
 * different sources can be:
 * <ul>
 *     <li><b>named safely</b> – see {@link #sanitizeFileName(String, ContentSource)}, which strips
 *         path separators (preventing a shared name like {@code ../../etc/passwd} from escaping the
 *         receive directory), removes characters that are illegal on common filesystems, and falls
 *         back to a sensible per-source default when no usable name is supplied;</li>
 *     <li><b>saved without overwriting</b> – see {@link #getNonCollidingFile(File, String)}, which
 *         appends {@code " (1)"}, {@code " (2)"}, … before the extension when a file with the same
 *         name already exists;</li>
 *     <li><b>dispatched per source</b> – see {@link #getDefaultAction(ContentSource)}, which decides
 *         whether the editor or the directory listing should be the primary post-save action.</li>
 * </ul>
 *
 * <p>The destination directory is resolved through {@link #getReceiveDirectoryPath()} instead of a
 * fixed constant. It defaults to {@link #DEFAULT_RECEIVE_DIR_PATH} but can be redirected via
 * {@link #setReceiveDirectoryPathOverride(String)}; this is the seam through which the directory is
 * made configurable (and through which tests point it at a scratch directory).
 */
public final class ShareStrategy {

    private ShareStrategy() {}

    /** The source the received content originated from. Used to pick safe defaults and the post-save action. */
    public enum ContentSource {
        /** Plain text shared via {@link android.content.Intent#ACTION_SEND} that is not a URL. */
        TEXT,
        /** Text shared via {@link android.content.Intent#ACTION_SEND} that is recognised as a URL/magnet link. */
        URL,
        /** A {@code content://} uri (shared stream or viewed document). */
        CONTENT_URI,
        /** A {@code file://} uri (viewed file). */
        FILE_URI
    }

    /** The action to run once the received content has been saved. */
    public enum SaveAction {
        /** Hand the saved file to {@code $HOME/bin/termux-file-editor}. */
        EDIT,
        /** Open a Termux session in the receive directory. */
        OPEN_DIRECTORY
    }

    /** Default directory received files are saved into when no override is configured. */
    public static final String DEFAULT_RECEIVE_DIR_PATH = TermuxConstants.TERMUX_FILES_DIR_PATH + "/home/downloads";

    /** Maximum length of a generated file name; ext4 allows 255 bytes, we cap on characters as a safe approximation. */
    static final int MAX_FILE_NAME_LENGTH = 255;

    /** Characters that are control characters or illegal/troublesome on common filesystems. */
    private static final Pattern ILLEGAL_FILE_NAME_CHARS = Pattern.compile("[\\x00-\\x1F/\\\\:*?\"<>|]");

    /** Optional override for {@link #getReceiveDirectoryPath()}; {@code null} means use the default. */
    private static volatile String sReceiveDirectoryPathOverride = null;

    /**
     * Configure the directory received content is saved into, overriding {@link #DEFAULT_RECEIVE_DIR_PATH}.
     *
     * @param path The directory path, or {@code null}/blank to fall back to the default.
     */
    public static void setReceiveDirectoryPathOverride(@Nullable String path) {
        sReceiveDirectoryPathOverride = (path == null || path.trim().isEmpty()) ? null : path;
    }

    /** @return The directory received content should be saved into. */
    @NonNull
    public static String getReceiveDirectoryPath() {
        final String override = sReceiveDirectoryPathOverride;
        return override != null ? override : DEFAULT_RECEIVE_DIR_PATH;
    }

    /** @return The post-save action that should be primary/highlighted for the given {@code source}. */
    @NonNull
    public static SaveAction getDefaultAction(@NonNull ContentSource source) {
        switch (source) {
            // Shared text/URLs are usually meant to be read or edited.
            case TEXT:
            case URL:
                return SaveAction.EDIT;
            // Shared/viewed files are often binaries (images, archives, …) the user wants to locate, not edit.
            case CONTENT_URI:
            case FILE_URI:
            default:
                return SaveAction.OPEN_DIRECTORY;
        }
    }

    /** @return The fallback base name used when no usable file name can be derived for {@code source}. */
    @NonNull
    static String getDefaultBaseName(@NonNull ContentSource source) {
        switch (source) {
            case TEXT:
                return "shared-text.txt";
            case URL:
                return "shared-url.txt";
            case CONTENT_URI:
            case FILE_URI:
            default:
                return "shared-file";
        }
    }

    /**
     * Produce a safe file name for the given {@code source} from a (possibly hostile, null or empty)
     * candidate name. The result is always a non-empty basename with no path separators, no illegal
     * characters and no leading/trailing dots, bounded to {@link #MAX_FILE_NAME_LENGTH} characters.
     *
     * @param candidate The candidate name (e.g. a display name from a content resolver, a file
     *                  basename, an intent subject, or user input). May be {@code null}.
     * @param source    The source the content came from, used to choose a default name when needed.
     * @return A sanitized, non-empty file name.
     */
    @NonNull
    public static String sanitizeFileName(@Nullable String candidate, @NonNull ContentSource source) {
        String name = candidate == null ? "" : candidate.trim();

        // Reduce to a basename so a name like "../../etc/passwd" or "sub/dir/file" cannot escape the
        // receive directory or create unexpected subdirectories.
        name = name.replace('\\', '/');
        final int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) name = name.substring(lastSlash + 1);

        // Replace control characters and characters that are illegal on common filesystems.
        name = ILLEGAL_FILE_NAME_CHARS.matcher(name).replaceAll("_");

        // Strip trailing dots/spaces. This also turns "." / ".." / "..." into an empty string, which
        // is then replaced by the per-source default below. Leading dots are kept so dotfiles survive.
        name = stripTrailingDotsAndSpaces(name);

        if (name.isEmpty())
            name = getDefaultBaseName(source);

        return enforceMaxLength(name);
    }

    /**
     * Return a {@link File} inside {@code directory} whose name does not collide with an existing
     * file, so saving never overwrites previously received content. If {@code fileName} is free it is
     * returned as-is; otherwise {@code " (1)"}, {@code " (2)"}, … is inserted before the extension.
     */
    @NonNull
    public static File getNonCollidingFile(@NonNull File directory, @NonNull String fileName) {
        File file = new File(directory, fileName);
        if (!file.exists()) return file;

        final String base;
        final String extension;
        final int dotIndex = fileName.lastIndexOf('.');
        // dotIndex > 0 so that dotfiles (".bashrc") are treated as having no extension.
        if (dotIndex > 0) {
            base = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        } else {
            base = fileName;
            extension = "";
        }

        for (int i = 1; i < Integer.MAX_VALUE; i++) {
            final File candidate = new File(directory, base + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }

        // Practically unreachable; fall back to the original (the writer will surface any error).
        return file;
    }

    private static String stripTrailingDotsAndSpaces(@NonNull String name) {
        int end = name.length();
        while (end > 0) {
            final char c = name.charAt(end - 1);
            if (c == '.' || c == ' ') end--;
            else break;
        }
        return name.substring(0, end);
    }

    private static String enforceMaxLength(@NonNull String name) {
        if (name.length() <= MAX_FILE_NAME_LENGTH) return name;

        final int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) {
            final String extension = name.substring(dotIndex);
            if (extension.length() < MAX_FILE_NAME_LENGTH)
                return name.substring(0, MAX_FILE_NAME_LENGTH - extension.length()) + extension;
        }
        return name.substring(0, MAX_FILE_NAME_LENGTH);
    }
}
