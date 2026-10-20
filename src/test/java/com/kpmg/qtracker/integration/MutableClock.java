package com.kpmg.qtracker.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** A Clock of Asia/Almaty the test sets and moves: the time of the scheduler, the dispatcher and the notifications. */
public class MutableClock extends Clock {

    public static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");

    private volatile Instant now;

    public MutableClock(LocalDateTime at) {
        set(at);
    }

    public MutableClock set(LocalDateTime at) {
        now = at.atZone(ALMATY).toInstant();
        return this;
    }

    public MutableClock plus(Duration duration) {
        now = now.plus(duration);
        return this;
    }

    public LocalDateTime now() {
        return LocalDateTime.ofInstant(now, ALMATY);
    }

    @Override
    public ZoneId getZone() {
        return ALMATY;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
