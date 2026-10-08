package com.kpmg.qtracker.service.userimport;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTestDatabaseTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:5432/QTracker",
            "jdbc:postgresql://LOCALHOST/QTracker",
            "jdbc:postgresql://127.0.0.1:5432/QTracker?sslmode=disable",
            "jdbc:postgresql://[::1]:5432/QTracker"
    })
    void localQTracker_mayBeReset(String url) {
        assertThat(LocalTestDatabase.refusal(url)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "jdbc:postgresql://db.example.test:5432/QTracker|not on this computer: db.example.test",
            "jdbc:postgresql://localhost.example.test/QTracker|not on this computer: localhost.example.test",
            "jdbc:postgresql://10.0.0.5:5432/QTracker|not on this computer: 10.0.0.5",
            "jdbc:postgresql://localhost:5432,db2.example.test:5432/QTracker|several database hosts",
            "jdbc:postgresql://localhost:5432/qtracker|is not QTracker: qtracker",
            "jdbc:postgresql://localhost:5432/QTracker_copy|is not QTracker: QTracker_copy",
            "jdbc:postgresql://localhost:5432/|is not QTracker",
            "jdbc:h2:mem:qtracker;MODE=PostgreSQL|not a PostgreSQL database on this computer: jdbc:h2:mem",
            "jdbc:oracle:thin:@localhost:1521:QTracker|not a PostgreSQL database on this computer: jdbc:oracle:thin"
    })
    void anyOtherDatabase_isRefused(String url, String reason) {
        assertThat(LocalTestDatabase.refusal(url)).hasValueSatisfying(refusal -> assertThat(refusal).contains(reason));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void noAddress_isRefused(String url) {
        assertThat(LocalTestDatabase.refusal(url)).isPresent();
    }
}
