package com.janemichael.jmrecruitingcrm.report;

import java.time.LocalDate;
import java.util.List;

/**
 * Outreach numbers for the last few weeks: what was sent, what came back, and which touches and
 * channels get replies.
 *
 * @param weeklyTarget the touch target for a week (five days at the daily target), or null for the team
 */
public record WeeklyReport(
        LocalDate today,
        Integer weeklyTarget,
        List<Week> weeks,
        List<ByTouch> byTouch,
        List<ByChannel> byChannel,
        List<Person> people
) {

    /**
     * One week, Monday to Sunday. The last one is the current week, so far.
     *
     * @param started contacts who got their very first touch that week
     * @param replies replies to touches sent by the person (or anyone, for the team)
     */
    public record Week(LocalDate start, int linkedin, int email, int call, int replies, int meetings, int started) {

        public int touches() {
            return linkedin + email + call;
        }
    }

    /**
     * How often each touch of a cold sequence got a reply before the next one, over the weeks shown.
     *
     * @param touch 1 to 4
     */
    public record ByTouch(int touch, int touches, int replies) {
    }

    /** Touches on each channel, and how many got a reply before the next touch, over the weeks shown. */
    public record ByChannel(String channel, int touches, int replies) {
    }

    /** One teammate's week so far (team view only). */
    public record Person(Long userId, String name, int touches, int replies, int meetings) {
    }
}
