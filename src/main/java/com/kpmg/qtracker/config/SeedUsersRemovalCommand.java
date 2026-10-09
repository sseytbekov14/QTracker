package com.kpmg.qtracker.config;

import com.kpmg.qtracker.service.userimport.SeedUsersRemoval;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * {@code java -jar qtracker.jar --remove-seed-users} prints which Flyway seed accounts a freshly created local
 * QTracker database would lose, writing nothing; add {@code --apply} to delete them (SeedUsersRemoval). Refused
 * (exit code 1) for any database but the local QTracker one and once it holds a control. Runs like
 * DeadlineRecalculationCommand: no web server, stops after the report.
 */
@Component
@RequiredArgsConstructor
public class SeedUsersRemovalCommand implements ApplicationRunner, ExitCodeGenerator {

    public static final String OPTION = "remove-seed-users";
    public static final String APPLY = "apply";

    private final SeedUsersRemoval removal;
    private final DataSource dataSource;
    private int exitCode;

    public static boolean requested(String... args) {
        return args != null && Arrays.asList(args).contains("--" + OPTION);
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        if (!args.containsOption(OPTION)) {
            return;
        }
        SeedUsersRemoval.Report report = removal.run(args.containsOption(APPLY), connectedUrl(),
                System.getProperty("user.name"));
        exitCode = report.refused() ? 1 : 0;
        System.out.print(report.render());
        System.out.flush();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    // The address the pool really uses, not a setting that could differ from it
    private String connectedUrl() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        }
    }
}
