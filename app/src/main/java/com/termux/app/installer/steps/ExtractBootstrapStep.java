package com.termux.app.installer.steps;

import com.termux.app.installer.InstallContext;
import com.termux.app.installer.InstallEnvironment;
import com.termux.app.installer.InstallStep;
import com.termux.app.installer.InstallationErrno;
import com.termux.shared.errors.Error;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts the bootstrap zip into the staging directory: regular files and directories are written
 * out (executables are chmod-ed), and the {@code SYMLINKS.txt} manifest is parsed to create the
 * bootstrap's internal symlinks once all entries are extracted.
 *
 * <p>File content is written with plain {@code java.io} (host-safe); directory creation, symlink
 * creation and chmod go through {@link InstallEnvironment} so the step is fully testable.
 */
public final class ExtractBootstrapStep implements InstallStep {

    /** The manifest entry inside the bootstrap zip listing the symlinks to create. */
    public static final String SYMLINKS_MANIFEST_ENTRY = "SYMLINKS.txt";

    /** Delimiter separating the symlink target from the link path on each SYMLINKS.txt line. */
    public static final String SYMLINK_DELIMITER = "←";

    private static final int BUFFER_SIZE = 8096;

    @Override
    public String getName() {
        return "extract-bootstrap";
    }

    @Override
    public Error execute(InstallContext context) {
        final InstallEnvironment environment = context.getEnvironment();
        final String stagingPath = context.getStagingPath();

        final byte[] zipBytes;
        try {
            zipBytes = environment.loadBootstrapZip();
        } catch (Throwable t) {
            return InstallationErrno.ERRNO_ZIP_LOAD_FAILED.getError(t, String.valueOf(t.getMessage()));
        }

        final List<Symlink> symlinks = new ArrayList<>(50);
        final byte[] buffer = new byte[BUFFER_SIZE];

        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInput.getNextEntry()) != null) {
                Error error;
                if (SYMLINKS_MANIFEST_ENTRY.equals(zipEntry.getName())) {
                    error = readSymlinksManifest(zipInput, stagingPath, environment, symlinks);
                } else {
                    error = extractEntry(zipInput, zipEntry, stagingPath, environment, buffer);
                }
                if (error != null) return error;
            }
        } catch (Exception e) {
            return InstallationErrno.ERRNO_EXTRACTING_BOOTSTRAP_FAILED.getError(e, stagingPath, String.valueOf(e.getMessage()));
        }

        if (symlinks.isEmpty())
            return InstallationErrno.ERRNO_NO_SYMLINKS_FOUND.getError();

        for (Symlink symlink : symlinks) {
            Error error = environment.createSymlink(symlink.target, symlink.linkPath);
            if (error != null) return error;
        }

        return null;
    }

    private Error readSymlinksManifest(ZipInputStream zipInput, String stagingPath,
                                       InstallEnvironment environment, List<Symlink> symlinks) throws Exception {
        // Explicit UTF-8: the delimiter is a multi-byte character, so the platform default charset
        // would mis-parse the manifest on a non-UTF-8 JVM.
        BufferedReader reader = new BufferedReader(new InputStreamReader(zipInput, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split(SYMLINK_DELIMITER);
            if (parts.length != 2)
                return InstallationErrno.ERRNO_MALFORMED_SYMLINK_LINE.getError(line);

            String target = parts[0];
            String linkPath = stagingPath + "/" + parts[1];
            symlinks.add(new Symlink(target, linkPath));

            Error error = environment.createDirectory(new File(linkPath).getParentFile().getAbsolutePath());
            if (error != null) return error;
        }
        return null;
    }

    private Error extractEntry(ZipInputStream zipInput, ZipEntry zipEntry, String stagingPath,
                               InstallEnvironment environment, byte[] buffer) throws Exception {
        String zipEntryName = zipEntry.getName();
        File targetFile = new File(stagingPath, zipEntryName);
        boolean isDirectory = zipEntry.isDirectory();

        Error error = environment.createDirectory((isDirectory ? targetFile : targetFile.getParentFile()).getAbsolutePath());
        if (error != null) return error;

        if (!isDirectory) {
            try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                int readBytes;
                while ((readBytes = zipInput.read(buffer)) != -1)
                    outStream.write(buffer, 0, readBytes);
            }
            if (isExecutableEntry(zipEntryName)) {
                error = environment.chmodExecutable(targetFile.getAbsolutePath());
                if (error != null) return error;
            }
        }
        return null;
    }

    /** The executable-bit predicate, preserved verbatim from the original installer. */
    private static boolean isExecutableEntry(String zipEntryName) {
        return zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec")
            || zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods");
    }

    private static final class Symlink {
        final String target;
        final String linkPath;

        Symlink(String target, String linkPath) {
            this.target = target;
            this.linkPath = linkPath;
        }
    }
}
