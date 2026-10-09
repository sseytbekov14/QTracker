package com.kpmg.qtracker.config;

import com.kpmg.qtracker.service.DeadlineOverdue;
import com.kpmg.qtracker.service.DeadlineRecalculation;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Arrays;

/**
 * {@code java -jar qtracker.jar --recalculate-deadlines} prints which controls that are not completed would
 * get the deadline of the current rule, writing nothing; add {@code --apply} to write them (history and
 * audit trail included). The app then starts without its web server and stops after the report
 * (QtrackerApplication.main), so it can run next to the running app against the same database.
 */
@Component
@RequiredArgsConstructor
public class DeadlineRecalculationCommand implements ApplicationRunner {

    public static final String OPTION = "recalculate-deadlines";
    public static final String APPLY = "apply";

    private final DeadlineRecalculation recalculation;
    private final Clock clock;

    public static boolean requested(String... args) {
        return args != null && Arrays.asList(args).contains("--" + OPTION);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        DeadlineRecalculation.Report report = recalculation.run(args.containsOption(APPLY),
                DeadlineOverdue.today(clock.instant()), System.getProperty("user.name"));
        System.out.print(report.render());
        System.out.flush();
    }
}
