package com.kpmg.qtracker;

import com.kpmg.qtracker.config.DeadlineRecalculationCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.ZoneId;

@SpringBootApplication
@EnableScheduling
public class QtrackerApplication {
    public static void main(String[] args) {
        if (DeadlineRecalculationCommand.requested(args)) {
            System.exit(SpringApplication.exit(runCommand(args)));
        }
        SpringApplication.run(QtrackerApplication.class, args);
    }

    /** A one-off command (DeadlineRecalculationCommand): no web server; the caller closes the context. */
    public static ConfigurableApplicationContext runCommand(String... args) {
        SpringApplication command = new SpringApplication(QtrackerApplication.class);
        command.setWebApplicationType(WebApplicationType.NONE);
        return command.run(args);
    }

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Almaty"));
    }
}
