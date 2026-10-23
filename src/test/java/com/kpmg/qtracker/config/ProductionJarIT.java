package com.kpmg.qtracker.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The jar of the usual build (the one the image takes, Dockerfile: COPY target/*.jar) holds nothing of the load
 * test: neither the loadtest profile's settings nor the classes of src/loadtest (its database guard, the data
 * generator with its made-up accounts). Those are built only with -Ploadtest, into target-loadtest/.
 * mvn verify gives the jar's path (pom.xml, failsafe); the loadtest build and a run from the IDE skip the check.
 */
class ProductionJarIT {

    @Test
    void theJarHasNoLoadTestCode() throws IOException {
        assumeTrue(Boolean.parseBoolean(System.getProperty("production.jar.check", "false")),
                "only the usual mvn verify checks its jar");
        Path jar = Path.of(System.getProperty("production.jar"));
        assertThat(jar).as("the jar mvn package made").exists();

        List<String> loadTest;
        try (JarFile file = new JarFile(jar.toFile())) {
            loadTest = file.stream().map(JarEntry::getName)
                    .filter(name -> name.contains("/loadtest/") || name.contains("application-loadtest"))
                    .toList();
        }
        assertThat(loadTest).as("load test entries in " + jar.getFileName()).isEmpty();
        assertThat(Files.size(jar)).isPositive();
    }
}
