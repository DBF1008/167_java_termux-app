package com.termux.shared.termux.settings.reload;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Utility for creating and modifying {@code termux.properties} files in the Robolectric shadow
 * filesystem during tests.
 */
class TestPropertiesHelper {

    /**
     * Create a {@code termux.properties} file at the given path with the supplied key-value pairs.
     * Parent directories are created automatically.
     *
     * @param propsFile target file
     * @param props     key-value pairs to write
     */
    static void writeProperties(File propsFile, Map<String, String> props) throws IOException {
        File parentDir = propsFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (Writer writer = new OutputStreamWriter(
            new FileOutputStream(propsFile), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : props.entrySet()) {
                writer.write(entry.getKey() + " = " + entry.getValue() + "\n");
            }
        }
    }

    /**
     * Update a single property in an existing file. Other keys are preserved only if the caller
     * re-writes them; this helper simply <em>appends</em> or overwrites the file.
     */
    static void writeSingleProperty(File propsFile, String key, String value) throws IOException {
        File parentDir = propsFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (Writer writer = new OutputStreamWriter(
            new FileOutputStream(propsFile), StandardCharsets.UTF_8)) {
            writer.write(key + " = " + value + "\n");
        }
    }

    /**
     * Delete the properties file (and silently ignore if it does not exist).
     */
    static void deletePropertiesFile(File propsFile) {
        if (propsFile.exists()) {
            propsFile.delete();
        }
    }

    /**
     * Write garbage content that is not valid Java properties format.
     */
    static void writeMalformedContent(File propsFile) throws IOException {
        File parentDir = propsFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (Writer writer = new OutputStreamWriter(
            new FileOutputStream(propsFile), StandardCharsets.UTF_8)) {
            writer.write("}}}not valid properties content!!!{{{\n");
            writer.write("\0\0\0binary garbage\0\0\n");
        }
    }
}
