package com.janemichael.jmrecruitingcrm.brief;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.brief.BriefDtos.AtRisk;
import com.janemichael.jmrecruitingcrm.brief.BriefDtos.Due;
import com.janemichael.jmrecruitingcrm.brief.BriefDtos.Facts;
import com.janemichael.jmrecruitingcrm.brief.BriefDtos.Interview;
import com.janemichael.jmrecruitingcrm.brief.BriefDtos.Money;
import com.janemichael.jmrecruitingcrm.brief.BriefDtos.Move;
import com.janemichael.jmrecruitingcrm.brief.BriefDtos.Outreach;
import com.janemichael.jmrecruitingcrm.brief.BriefDtos.PersonTouches;
import com.janemichael.jmrecruitingcrm.brief.BriefDtos.Posts;
import com.janemichael.jmrecruitingcrm.brief.BriefDtos.Stuck;
import com.janemichael.jmrecruitingcrm.candidate.Candidate;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineEntry;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineStage;
import com.janemichael.jmrecruitingcrm.search.Search;
import com.janemichael.jmrecruitingcrm.search.SearchEnums;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BriefFactsTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    /** A Wednesday. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);

    private final AppUser michael = user(1L, "Michael Drougas");
    private final Company cobalt = company(1L, "Cobalt Insurance");
    private long ids = 100;

    @Test
    void findsThisWeeksMonday() {
        assertThat(BriefFacts.mondayOf(TODAY)).isEqualTo(MONDAY);
        assertThat(BriefFacts.mondayOf(MONDAY)).isEqualTo(MONDAY);
        assertThat(BriefFacts.mondayOf(LocalDate.of(2026, 10, 25))).isEqualTo(MONDAY);
    }

    @Test
    void listsWhoMovedForwardLastWeekFurthestFirst() {
        Search search = search("Forward Deployed Engineer", SearchEnums.Status.ACTIVE, 30);
        Instant from = MONDAY.minusWeeks(1).atStartOfDay(ZONE).toInstant();
        Instant to = MONDAY.atStartOfDay(ZONE).toInstant();
        List<PipelineEntry> entries = List.of(
                entry(search, "Shortlisted Sam", PipelineStage.SHORTLISTED, MONDAY.minusDays(5)),
                entry(search, "Offer Olu", PipelineStage.OFFER, MONDAY.minusDays(3)),
                // Contacted isn't forward enough to count; this week's move is next week's news.
                entry(search, "Contacted Cara", PipelineStage.CONTACTED, MONDAY.minusDays(4)),
                entry(search, "This Week Tao", PipelineStage.INTERVIEWING, MONDAY.plusDays(1)));

        List<Move> moves = BriefFacts.moves(entries, from, to, ZONE);

        assertThat(moves).extracting(Move::candidate).containsExactly("Offer Olu", "Shortlisted Sam");
        assertThat(moves.getFirst().on()).isEqualTo(MONDAY.minusDays(3));
        assertThat(moves.getFirst().company()).isEqualTo("Cobalt Insurance");
    }

    @Test
    void flagsCandidatesAtOneStageTooLongUnlessSomethingIsBooked() {
        Search open = search("Forward Deployed Engineer", SearchEnums.Status.ACTIVE, 40);
        Search onHold = search("Paused role", SearchEnums.Status.ON_HOLD, 40);
        PipelineEntry booked = entry(open, "Booked Bea", PipelineStage.SUBMITTED, TODAY.minusDays(20));
        booked.setInterviewDate(TODAY.plusDays(2));
        List<PipelineEntry> entries = List.of(
                entry(open, "Submitted Sid", PipelineStage.SUBMITTED, TODAY.minusDays(9)),
                entry(open, "Fresh Fay", PipelineStage.SUBMITTED, TODAY.minusDays(3)),
                entry(open, "Assessing Ada", PipelineStage.ASSESSING, TODAY.minusDays(15)),
                entry(open, "Assessing Abe", PipelineStage.ASSESSING, TODAY.minusDays(10)),
                entry(open, "Sourced Sue", PipelineStage.SOURCED, TODAY.minusDays(60)),
                entry(onHold, "On Hold Hal", PipelineStage.OFFER, TODAY.minusDays(30)),
                booked);

        List<Stuck> stuck = BriefFacts.stuck(entries, TODAY, ZONE);

        assertThat(stuck).extracting(Stuck::candidate).containsExactly("Submitted Sid", "Assessing Ada");
        assertThat(stuck.getFirst().days()).isEqualTo(9);
        assertThat(stuck.getFirst().owner()).isEqualTo("Michael Drougas");
    }

    @Test
    void saysWhyAnOpenSearchNeedsAttention() {
        Search empty = search("No candidates yet", SearchEnums.Status.ACTIVE, 10);
        Search quiet = search("Quiet search", SearchEnums.Status.ACTIVE, 60);
        Search noShortlist = search("No shortlist", SearchEnums.Status.ACTIVE, 20);
        Search thin = search("Thin search", SearchEnums.Status.ACTIVE, 10);
        Search healthy = search("Healthy search", SearchEnums.Status.ACTIVE, 30);
        Search intake = search("Still in intake", SearchEnums.Status.INTAKE, 60);
        List<PipelineEntry> entries = new ArrayList<>(List.of(
                entry(quiet, "Quinn", PipelineStage.SHORTLISTED, TODAY.minusDays(20)),
                entry(noShortlist, "Nia", PipelineStage.ASSESSING, TODAY.minusDays(2)),
                entry(noShortlist, "Ned", PipelineStage.CONTACTED, TODAY.minusDays(2)),
                entry(noShortlist, "Noor", PipelineStage.CONTACTED, TODAY.minusDays(2)),
                entry(thin, "Tess", PipelineStage.SHORTLISTED, TODAY.minusDays(1))));
        for (String name : List.of("Hana", "Hugo", "Hiro")) {
            entries.add(entry(healthy, name, PipelineStage.SUBMITTED, TODAY.minusDays(2)));
        }

        List<AtRisk> risks = BriefFacts.atRisk(entries,
                List.of(empty, quiet, noShortlist, thin, healthy, intake), TODAY, ZONE);

        assertThat(risks).extracting(AtRisk::search)
                .containsExactly("Quiet search", "No shortlist", "No candidates yet", "Thin search");
        assertThat(risks).extracting(AtRisk::reason).containsExactly(
                "No movement in 20 days", "Nobody shortlisted, open 20 days", "No candidates yet, open 10 days",
                "Only 1 candidate in play");
    }

    @Test
    void listsThisWeeksInterviewsInOrderAndCountsDebriefsOwed() {
        Search search = search("Forward Deployed Engineer", SearchEnums.Status.ACTIVE, 30);
        PipelineEntry thursday = entry(search, "Thursday Thea", PipelineStage.INTERVIEWING, TODAY.minusDays(1));
        thursday.setInterviewDate(MONDAY.plusDays(3));
        PipelineEntry tuesdayLate = entry(search, "Tuesday Late", PipelineStage.INTERVIEWING, TODAY.minusDays(1));
        tuesdayLate.setInterviewDate(MONDAY.plusDays(1));
        tuesdayLate.setInterviewTime(LocalTime.of(15, 0));
        PipelineEntry tuesdayEarly = entry(search, "Tuesday Early", PipelineStage.INTERVIEWING, TODAY.minusDays(1));
        tuesdayEarly.setInterviewDate(MONDAY.plusDays(1));
        tuesdayEarly.setInterviewTime(LocalTime.of(9, 30));
        tuesdayEarly.setCandidateDebriefed(true);
        PipelineEntry nextWeek = entry(search, "Next Week Nell", PipelineStage.INTERVIEWING, TODAY.minusDays(1));
        nextWeek.setInterviewDate(MONDAY.plusDays(8));

        List<PipelineEntry> entries = List.of(thursday, tuesdayLate, tuesdayEarly, nextWeek);

        assertThat(BriefFacts.interviews(entries, MONDAY)).extracting(Interview::candidate)
                .containsExactly("Tuesday Early", "Tuesday Late", "Thursday Thea");
        // Tuesday's two are over; one still owes the client's debrief, the other both.
        assertThat(entries.stream().filter(entry -> BriefFacts.debriefOwed(entry, TODAY)).count()).isEqualTo(2);
    }

    @Test
    void describesTheFactsForTheAi() {
        Facts facts = new Facts(MONDAY, MONDAY.minusWeeks(1),
                new Outreach(142, 9, 3, 150, List.of(new PersonTouches("Michael Drougas", 80))),
                List.of(new Move(1L, "Marisol Okafor", 2L, "Forward Deployed Engineer", "Cobalt Insurance",
                        PipelineStage.INTERVIEWING, MONDAY.minusDays(3))),
                List.of(),
                List.of(new AtRisk(3L, "AI Platform Engineer", "Harbor Mutual", "No movement in 16 days", "Jane Drougas")),
                List.of(new Interview(1L, "Marisol Okafor", 2L, "Forward Deployed Engineer", "Cobalt Insurance",
                        MONDAY.plusDays(1), LocalTime.of(14, 0), "Round 2 with the CTO")),
                2,
                List.of(new Due("Michael Drougas", 8, 3, 1)),
                4,
                new Posts(3, 2, 1, 1840, 2),
                new Money(1, 2, 0));

        String text = BriefWriter.describe(facts, MONDAY);

        assertThat(text)
                .startsWith("Today is Monday, October 19, 2026. Last week ran from October 12 to October 18.")
                .contains("Last week's outreach: 142 touches (the target is 150 a person), 9 replies, 3 meetings.")
                .contains("Touches by person: Michael Drougas 80.")
                .contains("- Marisol Okafor to Interviewing, Forward Deployed Engineer at Cobalt Insurance")
                .contains("At one stage too long:\n- None")
                .contains("- AI Platform Engineer at Harbor Mutual: No movement in 16 days (Jane Drougas)")
                .contains("- Tuesday, Oct 20, 2:00 PM: Marisol Okafor, Round 2 with the CTO")
                .contains("Follow-ups due now: Michael Drougas 12 (8 contacts, 3 candidates, 1 clients)")
                .contains("2 posted last week (1840 impressions, 2 conversations)")
                .contains("Money: 1 overdue invoices; 2 guarantee check-ins due");
    }

    @Test
    void cleansTheAisLines() {
        assertThat(BriefWriter.lines(List.of("- Call Marisol — today", "2. Send the shortlist", "  ", "• Post"), 2))
                .containsExactly("Call Marisol, today", "Send the shortlist");
    }

    private PipelineEntry entry(Search search, String name, PipelineStage stage, LocalDate changed) {
        Candidate candidate = new Candidate();
        candidate.setId(ids++);
        candidate.setName(name);
        PipelineEntry entry = new PipelineEntry();
        entry.setId(ids++);
        entry.setSearch(search);
        entry.setCandidate(candidate);
        entry.setStage(stage);
        entry.setStageChangedAt(changed.atTime(10, 0).atZone(ZONE).toInstant());
        return entry;
    }

    private Search search(String title, SearchEnums.Status status, int openDays) {
        Search search = new Search();
        search.setId(ids++);
        search.setTitle(title);
        search.setCompany(cobalt);
        search.setStatus(status);
        search.setOpenedDate(TODAY.minusDays(openDays));
        search.setOwner(michael);
        return search;
    }

    private static Company company(long id, String name) {
        Company company = new Company();
        company.setId(id);
        company.setName(name);
        return company;
    }

    private static AppUser user(long id, String name) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setDisplayName(name);
        return user;
    }
}
