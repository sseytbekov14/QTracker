package com.kpmg.qtracker.config;

import com.kpmg.qtracker.service.RelatedControlsImport;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * {@code java -jar qtracker.jar --import-related-controls <file.xlsx>} (or {@code --import-related-controls=<file>})
 * prints what the import of the list Other Related Controls is chosen from would do, writing nothing; add
 * {@code --apply} to write it, and {@code --deactivate-missing} to make the Control IDs that are not in the file
 * inactive (RelatedControlsImport). Refused (exit code 1) for a file it cannot read. Runs as a one-off command
 * (QtrackerApplication.runCommand): no web server, stops after the report.
 */
@Component
@RequiredArgsConstructor
public class RelatedControlsImportCommand implements ApplicationRunner, ExitCodeGenerator {

    public static final String OPTION = "import-related-controls";
    public static final String APPLY = "apply";
    public static final String DEACTIVATE_MISSING = "deactivate-missing";

    private final RelatedControlsImport relatedControlsImport;
    private int exitCode;

    public static boolean requested(String... args) {
        return args != null && Arrays.stream(args)
                .anyMatch(arg -> arg.equals("--" + OPTION) || arg.startsWith("--" + OPTION + "="));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        String file = file(args);
        if (file == null) {
            exitCode = 1;
            System.out.println("Other Related Controls list import: REFUSED, nothing written: give the file, "
                    + "--import-related-controls <file.xlsx>");
            return;
        }
        RelatedControlsImport.Report report = relatedControlsImport.run(Path.of(file), args.containsOption(APPLY),
                args.containsOption(DEACTIVATE_MISSING), System.getProperty("user.name"));
        exitCode = report.refused() ? 1 : 0;
        System.out.print(report.render());
        System.out.flush();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    // --import-related-controls=<file>, or the first argument that is not an option
    private static String file(ApplicationArguments args) {
        List<String> values = args.getOptionValues(OPTION);
        if (values != null && !values.isEmpty() && !values.get(0).isBlank()) {
            return values.get(0);
        }
        return args.getNonOptionArgs().stream().filter(arg -> !arg.isBlank()).findFirst().orElse(null);
    }
}
