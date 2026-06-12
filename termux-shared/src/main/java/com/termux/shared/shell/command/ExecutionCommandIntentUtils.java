package com.termux.shared.shell.command;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.shell.command.result.ResultConfig;

/**
 * Shared sub-logic for parsing an {@link ExecutionCommand} out of an {@link android.content.Intent}.
 *
 * The plugin {@code RunCommandService} (reading the public {@code RUN_COMMAND_SERVICE.EXTRA_*} keys)
 * and {@code TermuxService} (reading the internal {@code TERMUX_SERVICE.EXTRA_*} keys) both build an
 * {@link ExecutionCommand} from intent extras. The two key sets are intentionally distinct (the
 * security boundary), but the decision/assignment logic around runner selection and result-directory
 * configuration used to be hand-duplicated and kept aligned by hand. That shared logic lives here so
 * both parsers stay consistent; each caller still reads its own keys and passes the values in.
 */
public final class ExecutionCommandIntentUtils {

    private ExecutionCommandIntentUtils() {}

    /**
     * Select the runner name shared by both intent parsers: if an explicit runner extra is set, use it;
     * otherwise default to {@link Runner#APP_SHELL} when a background run is requested, else
     * {@link Runner#TERMINAL_SESSION}.
     *
     * @param runnerExtra The value of the runner extra ({@code EXTRA_RUNNER}), or {@code null} if unset.
     * @param background The value of the background extra ({@code EXTRA_BACKGROUND}).
     * @return Returns the selected runner name.
     */
    @NonNull
    public static String selectRunner(@Nullable String runnerExtra, boolean background) {
        return runnerExtra != null
            ? runnerExtra
            : (background ? Runner.APP_SHELL.getName() : Runner.TERMINAL_SESSION.getName());
    }

    /**
     * Populate the result-directory fields of a {@link ResultConfig} from already-extracted intent
     * values. Should only be called when {@link ResultConfig#resultDirectoryPath} is set.
     *
     * @param resultConfig The {@link ResultConfig} to populate.
     * @param singleFile The value of the result-single-file extra.
     * @param fileBasename The value of the result-file-basename extra, or {@code null}.
     * @param fileOutputFormat The value of the result-file-output-format extra, or {@code null}.
     * @param fileErrorFormat The value of the result-file-error-format extra, or {@code null}.
     * @param filesSuffix The value of the result-files-suffix extra, or {@code null}.
     */
    public static void populateResultDirectoryConfig(@NonNull ResultConfig resultConfig, boolean singleFile,
                                                     @Nullable String fileBasename, @Nullable String fileOutputFormat,
                                                     @Nullable String fileErrorFormat, @Nullable String filesSuffix) {
        resultConfig.resultSingleFile = singleFile;
        resultConfig.resultFileBasename = fileBasename;
        resultConfig.resultFileOutputFormat = fileOutputFormat;
        resultConfig.resultFileErrorFormat = fileErrorFormat;
        resultConfig.resultFilesSuffix = filesSuffix;
    }

}
