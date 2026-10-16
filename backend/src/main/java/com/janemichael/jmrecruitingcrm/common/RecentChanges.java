package com.janemichael.jmrecruitingcrm.common;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    /** Single records, and batches from the bulk actions on the Contacts and Companies lists. */
    public enum Kind { CONTACT, CANDIDATE, COMPANY, CONTACT_BATCH, COMPANY_BATCH }

    /** What the record looked like before and after the change, and the history entry it wrote. */
    public record Change<S>(long userId, Long activityId, Instant at, S before, S after) {
    }

    private record Key(Kind kind, long recordId) {
    }

    private final Map<Key, Change<?>> changes = new ConcurrentHashMap<>();
    private final Clock clock;
    /** Batch numbers start from the clock, so a number from before a restart never finds a newer batch. */
    private final AtomicLong nextBatch;

    public RecentChanges(Clock clock) {
        this.clock = clock;
        this.nextBatch = new AtomicLong(clock.millis());
    }

    /** Remembers a bulk change and returns the number its Undo refers to. */
    public <S> long rememberBatch(Kind kind, long userId, S batch) {
        long number = nextBatch.incrementAndGet();
        remember(kind, number, userId, null, null, batch);
        return number;
    }

    /** Takes a bulk change back, once, for the person who made it. */
    public <S> S takeBatch(Kind kind, long number, long userId, Class<S> type) {
        return this.<S>take(kind, number, userId, type).after();
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
