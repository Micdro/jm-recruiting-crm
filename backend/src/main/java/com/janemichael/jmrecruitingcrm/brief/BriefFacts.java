package com.janemichael.jmrecruitingcrm.brief;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
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
import com.janemichael.jmrecruitingcrm.candidate.CandidateRepository;
import com.janemichael.jmrecruitingcrm.candidate.CandidateService;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.contact.ContactService;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostStatus;
import com.janemichael.jmrecruitingcrm.marketing.ContentPost;
import com.janemichael.jmrecruitingcrm.marketing.ContentPostRepository;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineEntry;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineRepository;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineStage;
import com.janemichael.jmrecruitingcrm.placement.Placement;
import com.janemichael.jmrecruitingcrm.placement.PlacementRepository;
import com.janemichael.jmrecruitingcrm.placement.PlacementService;
import com.janemichael.jmrecruitingcrm.search.Search;
import com.janemichael.jmrecruitingcrm.search.SearchEnums;
import com.janemichael.jmrecruitingcrm.search.SearchRepository;
import com.janemichael.jmrecruitingcrm.signals.SignalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The facts behind the Monday brief, from the records: last week's outreach and moves, who's been
 * at one stage too long, open searches that need attention, and what's on this week.
 */
@Service
@Transactional(readOnly = true)
public class BriefFacts {

    private static final Logger log = LoggerFactory.getLogger(BriefFacts.class);

    /** Shortlisted, submitted, interviewing or at offer this long without a change is too long. */
    static final int STUCK_LATE_DAYS = 7;
    /** Contacted or being assessed this long is too long. */
    static final int STUCK_EARLY_DAYS = 14;
    static final int QUIET_DAYS = 14;
    static final int NO_SHORTLIST_DAYS = 14;
    static final int NO_CANDIDATES_DAYS = 7;
    static final int THIN_PIPELINE = 3;
    static final int GUARANTEE_WINDOW_DAYS = 30;
    static final int MAX_LIST = 8;
    static final Set<PipelineStage> FORWARD = EnumSet.of(PipelineStage.SHORTLISTED, PipelineStage.SUBMITTED,
            PipelineStage.INTERVIEWING, PipelineStage.OFFER, PipelineStage.PLACED);
    static final Set<PipelineStage> LATE = EnumSet.of(PipelineStage.SHORTLISTED, PipelineStage.SUBMITTED,
            PipelineStage.INTERVIEWING, PipelineStage.OFFER);
    static final Set<PipelineStage> EARLY = EnumSet.of(PipelineStage.CONTACTED, PipelineStage.ASSESSING);
    static final String UNASSIGNED = "Unassigned";

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final PipelineRepository pipeline;
    private final SearchRepository searches;
    private final ContactRepository contacts;
    private final CandidateRepository candidates;
    private final CompanyRepository companies;
    private final ContentPostRepository posts;
    private final PlacementRepository placements;
    private final PlacementService placementService;
    private final SignalService signals;
    private final Clock clock;
    private final int dailyTouchTarget;

    public BriefFacts(JdbcTemplate jdbc, AppUserRepository users, PipelineRepository pipeline, SearchRepository searches,
                      ContactRepository contacts, CandidateRepository candidates, CompanyRepository companies,
                      ContentPostRepository posts, PlacementRepository placements, PlacementService placementService,
                      SignalService signals, Clock clock,
                      @Value("${app.outreach.daily-touch-target:30}") int dailyTouchTarget) {
        this.jdbc = jdbc;
        this.users = users;
        this.pipeline = pipeline;
        this.searches = searches;
        this.contacts = contacts;
        this.candidates = candidates;
        this.companies = companies;
        this.posts = posts;
        this.placements = placements;
        this.placementService = placementService;
        this.signals = signals;
        this.clock = clock;
        this.dailyTouchTarget = dailyTouchTarget;
    }

    /** This week's Monday. */
    public LocalDate weekOf() {
        return mondayOf(LocalDate.now(clock));
    }

    static LocalDate mondayOf(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public Facts gather() {
        LocalDate today = LocalDate.now(clock);
        LocalDate monday = mondayOf(today);
        LocalDate lastMonday = monday.minusWeeks(1);
        ZoneId zone = clock.getZone();
        Instant from = lastMonday.atStartOfDay(zone).toInstant();
        Instant to = monday.atStartOfDay(zone).toInstant();

        List<PipelineEntry> entries = pipeline.findAllWithDetails();
        return new Facts(
                monday,
                lastMonday,
                outreach(from, to),
                moves(entries, from, to, zone),
                stuck(entries, today, zone),
                atRisk(entries, searches.findAllWithDetails(), today, zone),
                interviews(entries, monday),
                (int) entries.stream().filter(entry -> debriefOwed(entry, today)).count(),
                due(today),
                newSignals(),
                posts(monday, lastMonday),
                money(today));
    }

    private Outreach outreach(Instant from, Instant to) {
        Map<Long, Integer> touchesByPerson = new HashMap<>();
        int[] totals = new int[3];
        jdbc.query("""
                SELECT user_id,
                       count(*) FILTER (WHERE touch) AS touches,
                       count(*) FILTER (WHERE type = 'REPLY') AS replies,
                       count(*) FILTER (WHERE type = 'MEETING') AS meetings
                FROM activity
                WHERE occurred_at >= ? AND occurred_at < ?
                GROUP BY user_id
                """, row -> {
            int touches = row.getInt("touches");
            totals[0] += touches;
            totals[1] += row.getInt("replies");
            totals[2] += row.getInt("meetings");
            long userId = row.getLong("user_id");
            if (!row.wasNull()) {
                touchesByPerson.merge(userId, touches, Integer::sum);
            }
        }, Timestamp.from(from), Timestamp.from(to));
        List<PersonTouches> people = users.findAllByOrderByDisplayNameAsc().stream()
                .filter(AppUser::isActive)
                .map(user -> new PersonTouches(user.getDisplayName(), touchesByPerson.getOrDefault(user.getId(), 0)))
                .toList();
        return new Outreach(totals[0], totals[1], totals[2], dailyTouchTarget * 5, people);
    }

    /** Candidates who reached the shortlist or further last week, the furthest first. */
    static List<Move> moves(List<PipelineEntry> entries, Instant from, Instant to, ZoneId zone) {
        return entries.stream()
                .filter(entry -> FORWARD.contains(entry.getStage()))
                .filter(entry -> !entry.getStageChangedAt().isBefore(from) && entry.getStageChangedAt().isBefore(to))
                .sorted(Comparator.comparing((PipelineEntry entry) -> entry.getStage().ordinal()).reversed()
                        .thenComparing(PipelineEntry::getStageChangedAt))
                .limit(MAX_LIST)
                .map(entry -> new Move(entry.getCandidate().getId(), entry.getCandidate().getName(),
                        entry.getSearch().getId(), entry.getSearch().getTitle(), entry.getSearch().getCompany().getName(),
                        entry.getStage(), entry.getStageChangedAt().atZone(zone).toLocalDate()))
                .toList();
    }

    /**
     * Candidates in an open search who've been at one stage too long, with nothing booked. The
     * furthest along first, since those cost the most to lose.
     */
    static List<Stuck> stuck(List<PipelineEntry> entries, LocalDate today, ZoneId zone) {
        record Waiting(PipelineEntry entry, int days) {
        }
        return entries.stream()
                .filter(entry -> open(entry.getSearch()))
                .filter(entry -> LATE.contains(entry.getStage()) || EARLY.contains(entry.getStage()))
                .filter(entry -> entry.getInterviewDate() == null || entry.getInterviewDate().isBefore(today))
                .map(entry -> new Waiting(entry, (int) ChronoUnit.DAYS.between(
                        entry.getStageChangedAt().atZone(zone).toLocalDate(), today)))
                .filter(waiting -> waiting.days() >= (LATE.contains(waiting.entry().getStage())
                        ? STUCK_LATE_DAYS : STUCK_EARLY_DAYS))
                .sorted(Comparator.comparing((Waiting waiting) -> waiting.entry().getStage().ordinal()).reversed()
                        .thenComparing(Comparator.comparing(Waiting::days).reversed()))
                .limit(MAX_LIST)
                .map(waiting -> {
                    PipelineEntry entry = waiting.entry();
                    return new Stuck(entry.getCandidate().getId(), entry.getCandidate().getName(),
                            entry.getSearch().getId(), entry.getSearch().getTitle(),
                            entry.getSearch().getCompany().getName(), entry.getStage(), waiting.days(),
                            ownerName(entry.getSearch().getOwner(), entry.getCandidate().getOwner()));
                })
                .toList();
    }

    /** Active searches that are quiet, thin or without a shortlist, the longest open first. */
    static List<AtRisk> atRisk(List<PipelineEntry> entries, List<Search> all, LocalDate today, ZoneId zone) {
        Map<Long, List<PipelineEntry>> bySearch = entries.stream()
                .collect(Collectors.groupingBy(entry -> entry.getSearch().getId()));
        record Risk(Search search, String reason, long open) {
        }
        List<Risk> risks = new ArrayList<>();
        for (Search search : all) {
            if (search.getStatus() != SearchEnums.Status.ACTIVE) {
                continue;
            }
            LocalDate opened = search.getOpenedDate() != null ? search.getOpenedDate()
                    : search.getCreatedAt() == null ? today : search.getCreatedAt().atZone(zone).toLocalDate();
            long open = ChronoUnit.DAYS.between(opened, today);
            List<PipelineEntry> its = bySearch.getOrDefault(search.getId(), List.of());
            Instant lastMove = its.stream().map(PipelineEntry::getStageChangedAt).max(Comparator.naturalOrder())
                    .orElse(null);
            long quiet = lastMove == null ? 0 : ChronoUnit.DAYS.between(lastMove.atZone(zone).toLocalDate(), today);
            long inPlay = its.stream().filter(entry -> entry.getStage().isActive()
                    && entry.getStage() != PipelineStage.SOURCED).count();
            boolean shortlisted = its.stream().anyMatch(entry -> FORWARD.contains(entry.getStage()));

            String reason = null;
            if (its.isEmpty() && open >= NO_CANDIDATES_DAYS) {
                reason = "No candidates yet, open " + open + " days";
            } else if (lastMove != null && quiet >= QUIET_DAYS) {
                reason = "No movement in " + quiet + " days";
            } else if (!shortlisted && open >= NO_SHORTLIST_DAYS) {
                reason = "Nobody shortlisted, open " + open + " days";
            } else if (!its.isEmpty() && inPlay < THIN_PIPELINE && open >= NO_CANDIDATES_DAYS
                    && its.stream().noneMatch(entry -> entry.getStage() == PipelineStage.OFFER
                    || entry.getStage() == PipelineStage.PLACED)) {
                reason = "Only " + inPlay + (inPlay == 1 ? " candidate" : " candidates") + " in play";
            }
            if (reason != null) {
                risks.add(new Risk(search, reason, open));
            }
        }
        return risks.stream()
                .sorted(Comparator.comparing(Risk::open).reversed())
                .limit(MAX_LIST)
                .map(risk -> new AtRisk(risk.search().getId(), risk.search().getTitle(),
                        risk.search().getCompany().getName(), risk.reason(), ownerName(risk.search().getOwner(), null)))
                .toList();
    }

    static List<Interview> interviews(List<PipelineEntry> entries, LocalDate monday) {
        LocalDate sunday = monday.plusDays(6);
        return entries.stream()
                .filter(entry -> entry.getStage().isActive() && entry.getInterviewDate() != null)
                .filter(entry -> !entry.getInterviewDate().isBefore(monday) && !entry.getInterviewDate().isAfter(sunday))
                .sorted(Comparator.comparing(PipelineEntry::getInterviewDate)
                        .thenComparing(entry -> entry.getInterviewTime() == null ? LocalTime.MAX : entry.getInterviewTime()))
                .limit(MAX_LIST * 2L)
                .map(entry -> new Interview(entry.getCandidate().getId(), entry.getCandidate().getName(),
                        entry.getSearch().getId(), entry.getSearch().getTitle(), entry.getSearch().getCompany().getName(),
                        entry.getInterviewDate(), entry.getInterviewTime(), entry.getInterviewLabel()))
                .toList();
    }

    static boolean debriefOwed(PipelineEntry entry, LocalDate today) {
        return entry.getStage().isActive() && entry.getInterviewDate() != null && entry.getInterviewDate().isBefore(today)
                && !(entry.isCandidateDebriefed() && entry.isClientDebriefed());
    }

    /** Follow-ups due now, per person, the most first. */
    private List<Due> due(LocalDate today) {
        Map<String, int[]> counts = new HashMap<>();
        for (Contact contact : contacts.findAllWithDetails()) {
            if (ContactService.isDue(contact, today)) {
                counts.computeIfAbsent(ownerName(contact.getOwner(), null), name -> new int[3])[0]++;
            }
        }
        for (Candidate candidate : candidates.findAllWithOwner()) {
            if (CandidateService.isDue(candidate, today)) {
                counts.computeIfAbsent(ownerName(candidate.getOwner(), null), name -> new int[3])[1]++;
            }
        }
        for (Company company : companies.findAllWithOwner()) {
            if (company.getFollowUpDate() != null && !company.getFollowUpDate().isAfter(today)) {
                counts.computeIfAbsent(ownerName(company.getOwner(), null), name -> new int[3])[2]++;
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new Due(entry.getKey(), entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]))
                .sorted(Comparator.comparing(Due::total).reversed().thenComparing(Due::name))
                .toList();
    }

    /** Companies with roles nobody has reviewed yet. Never stops the brief if the cache can't be read. */
    private int newSignals() {
        try {
            return signals.summary().companies();
        } catch (RuntimeException unreadable) {
            log.warn("Could not count hiring signals for the brief", unreadable);
            return 0;
        }
    }

    private Posts posts(LocalDate monday, LocalDate lastMonday) {
        LocalDate sunday = monday.plusDays(6);
        LocalDate lastSunday = monday.minusDays(1);
        int planned = 0;
        int posted = 0;
        int missed = 0;
        int impressions = 0;
        int conversations = 0;
        for (ContentPost post : posts.findAllWithOwner()) {
            LocalDate day = post.getPlannedFor();
            boolean up = post.getStatus() == PostStatus.POSTED;
            if (!up && day != null && !day.isBefore(monday) && !day.isAfter(sunday)) {
                planned++;
            }
            if (!up && day != null && !day.isBefore(lastMonday) && !day.isAfter(lastSunday)) {
                missed++;
            }
            LocalDate postedOn = post.getPostedOn();
            if (up && postedOn != null && !postedOn.isBefore(lastMonday) && !postedOn.isAfter(lastSunday)) {
                posted++;
                impressions += Objects.requireNonNullElse(post.getImpressions(), 0);
                conversations += Objects.requireNonNullElse(post.getConversations(), 0);
            }
        }
        return new Posts(planned, posted, missed, impressions, conversations);
    }

    private Money money(LocalDate today) {
        List<Placement> all = placements.findAllWithDetails();
        int overdue = (int) all.stream()
                .filter(placement -> placement.getInvoiceStatus() == Placement.InvoiceStatus.INVOICED)
                .filter(placement -> placement.effectiveDueDate() != null && placement.effectiveDueDate().isBefore(today))
                .count();
        LocalDate windowEnd = today.plusDays(GUARANTEE_WINDOW_DAYS);
        int ending = (int) all.stream()
                .map(Placement::getGuaranteeEndDate)
                .filter(Objects::nonNull)
                .filter(end -> !end.isBefore(today) && !end.isAfter(windowEnd))
                .count();
        return new Money(overdue, placementService.checkInsDue().size(), ending);
    }

    private static boolean open(Search search) {
        return search.getStatus() == SearchEnums.Status.ACTIVE || search.getStatus() == SearchEnums.Status.INTAKE;
    }

    private static String ownerName(AppUser first, AppUser second) {
        AppUser owner = first != null ? first : second;
        return owner == null ? UNASSIGNED : owner.getDisplayName();
    }
}
