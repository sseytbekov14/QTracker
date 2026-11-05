package com.kpmg.qtracker.config;

import com.kpmg.qtracker.service.DataRetention;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * {@code java -jar qtracker.jar --purge-expired} prints what the data retention (DataRetention: seven years from
 * creation) would delete, deleting nothing; add {@code --apply} to delete it. Refused (exit code 1) for a retention of
 * fewer than seven years. Runs as a one-off command (QtrackerApplication.runCommand): no web server, stops after the
 * report. The same runs by itself every day (DataRetentionTrigger), which also clears the text of e-mails done more
 * than 30 days ago.
 */
@Component
@RequiredArgsConstructor
public class DataRetentionCommand implements ApplicationRunner, ExitCodeGenerator {

    public static final String OPTION = "purge-expired";
    public static final String APPLY = "apply";

    private final DataRetention dataRetention;
    private int exitCode;

    public static boolean requested(String... args) {
        return args != null && Arrays.stream(args).anyMatch(arg -> arg.equals("--" + OPTION));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        DataRetention.Report report = dataRetention.run(args.containsOption(APPLY), System.getProperty("user.name"));
        exitCode = report.refused() ? 1 : 0;
        System.out.print(report.render());
        System.out.flush();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
