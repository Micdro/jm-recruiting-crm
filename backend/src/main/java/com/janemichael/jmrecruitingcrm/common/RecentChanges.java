package com.janemichael.jmrecruitingcrm.common;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each person's last change to a record, kept in memory for a few minutes so a misclick can be
 * undone from the confirmation. Nothing is written to disk: each computer keeps its own, so a
 * change is undone on the computer it was made on. Undoing it puts the record back the way it
 * was and removes the history entry the change wrote.
 */
@Component
public class RecentChanges {

    public static final Duration WINDOW = Duration.ofMinutes(10);

    public static final String NOTHING_TO_UNDO =
            "There's nothing to undo. Undo works for 10 minutes after a change.";

    public enum Kind { CONTACT, CANDIDATE, COMPANY }

    /** What the record looked like before and after the change, and the history entry it wrote. */
    public record Change<S>(long userId, Long activityId, Instant at, S before, S after) {
    }

    private record Key(Kind kind, long recordId) {
    }

    private final Map<Key, Change<?>> changes = new ConcurrentHashMap<>();
    private final Clock clock;

    public RecentChanges(Clock clock) {
        this.clock = clock;
    }

    /** Remembers a change, replacing any earlier one to the same record. */
    public <S> void remember(Kind kind, long recordId, long userId, Long activityId, S before, S after) {
        Instant now = clock.instant();
        changes.values().removeIf(change -> expired(change, now));
        changes.put(new Key(kind, recordId), new Change<>(userId, activityId, now, before, after));
    }

    /**
     * Takes the person's last change to a record, so it can be undone once. Throws when there is
     * none: it was someone else's, it's too old, or it was already undone.
     */
    @SuppressWarnings("unchecked")
    public <S> Change<S> take(Kind kind, long recordId, long userId, Class<S> type) {
        Key key = new Key(kind, recordId);
        Change<?> change = changes.get(key);
        if (change == null
                || change.userId() != userId
                || expired(change, clock.instant())
                || !type.isInstance(change.after())
                || !changes.remove(key, change)) {
            throw new ConflictException(NOTHING_TO_UNDO);
        }
        return (Change<S>) change;
    }

    private static boolean expired(Change<?> change, Instant now) {
        return change.at().plus(WINDOW).isBefore(now);
    }
}
