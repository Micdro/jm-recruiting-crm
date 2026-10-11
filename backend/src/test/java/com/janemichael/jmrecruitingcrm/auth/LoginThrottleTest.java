package com.janemichael.jmrecruitingcrm.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T14:00:00Z"));
    private final LoginThrottle throttle = new LoginThrottle(clock);

    @Test
    void locksAnAccountAfterFiveFailuresForFifteenMinutes() {
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("michael@jane-michael.com", "203.0.113." + i);
        }
        assertThat(throttle.blockedFor("michael@jane-michael.com", "198.51.100.1")).isZero();

        throttle.recordFailure("MICHAEL@jane-michael.com ", "203.0.113.9");
        assertThat(throttle.blockedFor("michael@jane-michael.com", "198.51.100.1"))
                .isEqualTo(Duration.ofMinutes(15));

        clock.advance(Duration.ofMinutes(10));
        assertThat(throttle.blockedFor("michael@jane-michael.com", "198.51.100.1"))
                .isEqualTo(Duration.ofMinutes(5));

        clock.advance(Duration.ofMinutes(5));
        assertThat(throttle.blockedFor("michael@jane-michael.com", "198.51.100.1")).isZero();
    }

    @Test
    void locksAnAddressThatTriesManyAccounts() {
        for (int i = 0; i < 20; i++) {
            throttle.recordFailure("guess" + i + "@example.com", "203.0.113.7");
        }

        assertThat(throttle.blockedFor("jane@jane-michael.com", "203.0.113.7")).isPositive();
        assertThat(throttle.blockedFor("jane@jane-michael.com", "198.51.100.1")).isZero();
    }

    @Test
    void aSuccessfulSignInClearsTheAccountsFailures() {
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("jane@jane-michael.com", "203.0.113.7");
        }
        throttle.recordSuccess("jane@jane-michael.com");
        throttle.recordFailure("jane@jane-michael.com", "203.0.113.7");

        assertThat(throttle.blockedFor("jane@jane-michael.com", "198.51.100.1")).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
