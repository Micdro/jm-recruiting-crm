package com.janemichael.jmrecruitingcrm.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows password guessing. After 5 failed sign-ins for one account, or 20 from one network address,
 * within 15 minutes, further attempts are refused until the oldest failure ages out.
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES_PER_ACCOUNT = 5;
    static final int MAX_FAILURES_PER_ADDRESS = 20;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** How long sign-in is blocked for this account or address. Zero when it isn't. */
    public Duration blockedFor(String email, String address) {
        Instant now = Instant.now(clock);
        Duration account = blockedFor(accountKey(email), MAX_FAILURES_PER_ACCOUNT, now);
        Duration network = blockedFor(addressKey(address), MAX_FAILURES_PER_ADDRESS, now);
        return account.compareTo(network) >= 0 ? account : network;
    }

    public void recordFailure(String email, String address) {
        Instant now = Instant.now(clock);
        if (failures.size() > MAX_TRACKED_KEYS) {
            failures.clear();
        }
        record(accountKey(email), now);
        record(addressKey(address), now);
    }

    public void recordSuccess(String email) {
        failures.remove(accountKey(email));
    }

    private Duration blockedFor(String key, int limit, Instant now) {
        Deque<Instant> times = failures.get(key);
        if (times == null) {
            return Duration.ZERO;
        }
        synchronized (times) {
            prune(times, now);
            if (times.size() < limit) {
                return Duration.ZERO;
            }
            Duration remaining = Duration.between(now, times.peekFirst().plus(WINDOW));
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }
    }

    private void record(String key, Instant now) {
        Deque<Instant> times = failures.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (times) {
            prune(times, now);
            times.addLast(now);
        }
    }

    private static void prune(Deque<Instant> times, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
            times.pollFirst();
        }
    }

    private static String accountKey(String email) {
        return "account:" + (email == null ? "" : email.trim().toLowerCase());
    }

    private static String addressKey(String address) {
        return "address:" + (address == null ? "" : address);
    }
}
