package com.termux.app.install.steps;

import com.termux.app.install.InstallContext;
import com.termux.app.install.InstallErrno;
import com.termux.app.install.InstallState;
import com.termux.app.install.InstallStep;
import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Loads the bootstrap zip bytes, extracts all entries into the staging directory,
 * and parses {@code SYMLINKS.txt} into the shared {@link InstallContext#getParsedSymlinks()} list.
 * <p>
 * The zip loading is abstracted via {@link ZipBytesProvider} to decouple from
 * the native library call ({@code System.loadLibrary("termux-bootstrap")}).
 * <p>
 * <b>Key change from original</b>: All {@code RuntimeException}s from the original code
 * (malformed symlinks, missing SYMLINKS.txt, IO errors) are caught and converted
 * to structured {@link Error} objects.
 */
public class ExtractBootstrapStep implements InstallStep {

    private static final String LOG_TAG = "ExtractBootstrapStep";
    private static final int BUFFER_SIZE = 8096;

    /**
     * Functional interface to abstract zip byte loading.
     * Production code uses {@code TermuxInstaller::loadZipBytes}.
     * Tests can provide a lambda with in-memory bytes.
     */
    public interface ZipBytesProvider {
        byte[] loadZipBytes() throws Exception;
    }

    private final ZipBytesProvider zipProvider;

    public ExtractBootstrapStep(ZipBytesProvider zipProvider) {
        this.zipProvider = zipProvider;
    }

    @Override
    public String getName() {
        return "Extract Bootstrap";
    }

    @Override
    public InstallState getResultingState() {
        return InstallState.EXTRACTING;
    }

    @Override
    public Error execute(InstallContext context) {
        // Clear previously parsed symlinks for idempotent re-execution
        context.getParsedSymlinks().clear();

        // Load zip bytes
        byte[] zipBytes;
        try {
            zipBytes = zipProvider.loadZipBytes();
        } catch (Exception e) {
            return InstallErrno.ERRNO_ZIP_LOAD_FAILED.getError(e, e.getMessage());
        }

        String stagingDirPath = context.getStagingDirPath();
        byte[] buffer = new byte[BUFFER_SIZE];

        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInput.getNextEntry()) != null) {
                if (zipEntry.getName().equals("SYMLINKS.txt")) {
                    Error error = parseSymlinksTxt(zipInput, stagingDirPath, context);
                    if (error != null) return error;
                } else {
                    Error error = extractZipEntry(zipEntry, zipInput, stagingDirPath, buffer);
                    if (error != null) return error;
                }
            }
        } catch (Exception e) {
            return InstallErrno.ERRNO_ZIP_EXTRACT_FAILED.getError(e, e.getMessage());
        }

        if (context.getParsedSymlinks().isEmpty()) {
            return InstallErrno.ERRNO_NO_SYMLINKS_TXT.getError();
        }

        return null;
    }

    @Override
    public Error rollback(InstallContext context) {
        return FileUtils.deleteFile("staging rollback",
            context.getStagingDirPath(), true);
    }

    private Error parseSymlinksTxt(ZipInputStream zipInput, String stagingDirPath,
                                   InstallContext context) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(zipInput));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("←"); // ← character
                if (parts.length != 2) {
                    return InstallErrno.ERRNO_MALFORMED_SYMLINKS_TXT.getError(line);
                }
                String targetPath = parts[0];
                String linkPath = stagingDirPath + "/" + parts[1];
                context.getParsedSymlinks().add(new String[]{targetPath, linkPath});

                // Ensure parent directory of link exists
                Error error = ensureDirectoryExists(new File(linkPath).getParentFile());
                if (error != null) return error;
            }
        } catch (Exception e) {
            return InstallErrno.ERRNO_ZIP_EXTRACT_FAILED.getError(e,
                "Failed to parse SYMLINKS.txt: " + e.getMessage());
        }
        return null;
    }

    private Error extractZipEntry(ZipEntry zipEntry, ZipInputStream zipInput,
                                  String stagingDirPath, byte[] buffer) {
        String zipEntryName = zipEntry.getName();
        File targetFile = new File(stagingDirPath, zipEntryName);
        boolean isDirectory = zipEntry.isDirectory();

        Error error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
        if (error != null) return error;

        if (!isDirectory) {
            try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                int readBytes;
                while ((readBytes = zipInput.read(buffer)) != -1) {
                    outStream.write(buffer, 0, readBytes);
                }
            } catch (Exception e) {
                return InstallErrno.ERRNO_ZIP_EXTRACT_FAILED.getError(e,
                    "Failed to extract " + zipEntryName + ": " + e.getMessage());
            }

            // Set executable permissions for binaries and apt helpers
            if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec")
                || zipEntryName.startsWith("lib/apt/apt-helper")
                || zipEntryName.startsWith("lib/apt/methods")) {
                try {
                    android.system.Os.chmod(targetFile.getAbsolutePath(), 0700);
                } catch (Exception e) {
                    Logger.logWarn(LOG_TAG, "Failed to chmod " + targetFile.getAbsolutePath()
                        + ": " + e.getMessage());
                }
            }
        }

        return null;
    }

    private static Error ensureDirectoryExists(File directory) {
        Error error = FileUtils.createDirectoryFile(directory.getAbsolutePath());
        if (error != null) {
            return InstallErrno.ERRNO_ENSURE_DIR_FAILED.getError(
                Error.getErrorMarkdownString(error));
        }
        return null;
    }
}
