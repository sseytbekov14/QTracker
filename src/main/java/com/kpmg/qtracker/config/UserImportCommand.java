package com.kpmg.qtracker.config;

import com.kpmg.qtracker.service.userimport.UserImport;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * {@code java -jar qtracker.jar --import-users <file>} (or {@code --import-users=<file>}) prints what the import
 * of the people file would create, writing nothing but the logins file; add {@code --apply} to create the
 * accounts (UserImport). Refused (exit code 1) outside the dev and test profiles, for a file it cannot read and
 * without two active SoQM Team members. Runs like DeadlineRecalculationCommand: no web server, stops after
 * the report.
 */
@Component
@RequiredArgsConstructor
public class UserImportCommand implements ApplicationRunner, ExitCodeGenerator {

    public static final String OPTION = "import-users";
    public static final String APPLY = "apply";

    private final UserImport userImport;
    private final Clock clock;
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
            System.out.println("User import: REFUSED, nothing written: give the file, --import-users <file>");
            return;
        }
        UserImport.Report report = userImport.run(Path.of(file), args.containsOption(APPLY),
                System.getProperty("user.name"), LocalDate.now(clock));
        exitCode = report.refused() ? 1 : 0;
        System.out.print(report.render());
        System.out.flush();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    // --import-users=<file>, or the first argument that is not an option
    private static String file(ApplicationArguments args) {
        List<String> values = args.getOptionValues(OPTION);
        if (values != null && !values.isEmpty() && !values.get(0).isBlank()) {
            return values.get(0);
        }
        return args.getNonOptionArgs().stream().filter(arg -> !arg.isBlank()).findFirst().orElse(null);
    }
}
