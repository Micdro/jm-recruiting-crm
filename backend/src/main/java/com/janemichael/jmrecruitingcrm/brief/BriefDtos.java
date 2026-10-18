package com.janemichael.jmrecruitingcrm.brief;

import com.janemichael.jmrecruitingcrm.pipeline.PipelineStage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** The Monday brief: the week's facts from the CRM, and what AI wrote from them. */
public final class BriefDtos {

    private BriefDtos() {
    }

    /**
     * What happened last week and what's on this week, for the whole team. Worked out fresh each
     * time from the records, so it's always current.
     *
     * @param weekOf   this week's Monday
     * @param lastWeek last week's Monday
     */
    public record Facts(
            LocalDate weekOf,
            LocalDate lastWeek,
            Outreach outreach,
            List<Move> moves,
            List<Stuck> stuck,
            List<AtRisk> atRisk,
            List<Interview> interviews,
            int debriefsOwed,
            List<Due> due,
            int newSignals,
            Posts posts,
            Money money
    ) {
    }

    /**
     * Last week's outreach.
     *
     * @param target each person's touch target for a week
     */
    public record Outreach(int touches, int replies, int meetings, int target, List<PersonTouches> people) {
    }

    public record PersonTouches(String name, int touches) {
    }

    /** A candidate who moved forward in a search last week. */
    public record Move(Long candidateId, String candidate, Long searchId, String search, String company,
                       PipelineStage stage, LocalDate on) {
    }

    /** A candidate whose stage hasn't changed in a while. */
    public record Stuck(Long candidateId, String candidate, Long searchId, String search, String company,
                        PipelineStage stage, int days, String owner) {
    }

    /** An open search that needs attention, and why. */
    public record AtRisk(Long searchId, String search, String company, String reason, String owner) {
    }

    public record Interview(Long candidateId, String candidate, Long searchId, String search, String company,
                            LocalDate on, LocalTime at, String label) {
    }

    /** Follow-ups due now (today or earlier) for one person. */
    public record Due(String name, int contacts, int candidates, int companies) {

        public int total() {
            return contacts + candidates + companies;
        }
    }

    /**
     * @param plannedThisWeek posts planned for this week that aren't up yet
     * @param missedLastWeek  posts planned for last week that never went up
     */
    public record Posts(int plannedThisWeek, int postedLastWeek, int missedLastWeek, int impressionsLastWeek,
                        int conversationsLastWeek) {
    }

    /**
     * @param guaranteesEnding guarantee periods ending in the next 30 days
     */
    public record Money(int overdueInvoices, int checkInsDue, int guaranteesEnding) {
    }

    /** What AI wrote for the week. */
    public record Written(String headline, List<String> lastWeek, List<String> watch, List<String> focus,
                          Instant writtenAt, String writtenBy, String provider, int costCents) {
    }

    /**
     * @param written  this week's write-up, or null if it isn't written yet
     * @param provider the AI that writes it, like "Claude"
     * @param canWrite whether that AI has its key, so the brief can be written
     */
    public record BriefResponse(LocalDate weekOf, Facts facts, Written written, String provider, boolean canWrite,
                                String owner) {
    }
}
