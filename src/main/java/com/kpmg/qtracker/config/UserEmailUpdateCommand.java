package com.kpmg.qtracker.config;

import com.kpmg.qtracker.service.userimport.UserEmailUpdate;
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
 * {@code java -jar qtracker.jar --update-user-emails <file>} (or {@code --update-user-emails=<file>}) prints which
 * people would get which e-mail (UserEmailUpdate), writing nothing but the list next to the file; add
 * {@code --apply} to change them. Refused (exit code 1, nothing changed) for a file it cannot read or any problem
 * in it. Runs like UserImportCommand: no web server, stops after the report; in any profile (it is meant for the
 * database SSO will run on).
 */
@Component
@RequiredArgsConstructor
public class UserEmailUpdateCommand implements ApplicationRunner, ExitCodeGenerator {

    public static final String OPTION = "update-user-emails";
    public static final String APPLY = "apply";

    private final UserEmailUpdate update;
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
            System.out.println("E-mail update: REFUSED, nothing written: give the file, --" + OPTION + " <file>");
            return;
        }
        UserEmailUpdate.Report report = update.run(Path.of(file), args.containsOption(APPLY),
                System.getProperty("user.name"), LocalDate.now(clock));
        exitCode = report.refused() ? 1 : 0;
        System.out.print(report.render());
        System.out.flush();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    // --update-user-emails=<file>, or the first argument that is not an option
    private static String file(ApplicationArguments args) {
        List<String> values = args.getOptionValues(OPTION);
        if (values != null && !values.isEmpty() && !values.get(0).isBlank()) {
            return values.get(0);
        }
        return args.getNonOptionArgs().stream().filter(arg -> !arg.isBlank()).findFirst().orElse(null);
    }
}
