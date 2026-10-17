package com.janemichael.jmrecruitingcrm.prospects;

import com.janemichael.jmrecruitingcrm.activity.ActivityLogger;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.EnumCsv;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyEnums;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.company.CompanyStatus;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.contact.OutreachStatus;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.DismissRequest;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.FoundCompany;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.FoundContact;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.KeyRequest;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.LastRun;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.LimitsRequest;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.Reviewed;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.SettingsRequest;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.Status;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.Summary;
import com.janemichael.jmrecruitingcrm.prospects.ProspectResearch.Outcome;
import com.janemichael.jmrecruitingcrm.prospects.ProspectResearch.ResearchException;
import com.janemichael.jmrecruitingcrm.prospects.ProspectResearch.Usage;
import com.janemichael.jmrecruitingcrm.prospects.ProspectSettings.Run;
import com.janemichael.jmrecruitingcrm.prospects.ProspectSettings.Values;
import com.janemichael.jmrecruitingcrm.sync.SyncIdentity;
import com.janemichael.jmrecruitingcrm.trash.TrashKind;
import com.janemichael.jmrecruitingcrm.trash.TrashService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * The prospect finder: each weekday, Claude searches the web for AI and security vendors selling into
 * financial services that show a reason to hire now, and adds them to the CRM as "AI found", with the
 * people who would hire. The team accepts them (they become leads) or dismisses them.
 *
 * <ul>
 *   <li>One computer runs it on schedule, the first time the CRM is unlocked on a weekday from 7 AM,
 *       so three computers don't pay for three searches. Anyone can start one with Find now.</li>
 *   <li>The API key, settings, last run and this month's spend are shared settings, synced and encrypted.</li>
 *   <li>Companies already in the CRM, under any stage (dismissed ones included), are never added again.</li>
 * </ul>
 */
@Service
public class ProspectFinder {

    private static final Logger log = LoggerFactory.getLogger(ProspectFinder.class);

    static final LocalTime EARLIEST = LocalTime.of(7, 0);
    /** A run still marked running after this long stopped without finishing (the app closed, say). */
    static final Duration STALE = Duration.ofMinutes(45);
    static final int MAX_EXCLUDED_NAMES = 1500;
    /** A search needs at least this much of the month's limit left to be worth starting. */
    static final int MIN_RUN_CENTS = 100;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    record Saved(int added, int skipped, int contacts) {
    }

    /**
     * What a search may spend now: what's left of today's limit, or of this month's if that's less.
     *
     * @param limitName the limit that decides it, like "today's $2 limit"
     * @param blocked   why no search can start now, or null when one can
     */
    record Budget(int cents, String limitName, String blocked) {
    }

    private final ProspectSettings settings;
    private final ProspectResearch research;
    private final ClaudeApi api;
    private final CompanyRepository companies;
    private final ContactRepository contacts;
    private final AppUserRepository users;
    private final CurrentUserService currentUser;
    private final ActivityLogger activity;
    private final TrashService trash;
    private final FieldEncryptor encryptor;
    private final SyncIdentity identity;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    public ProspectFinder(
            ProspectSettings settings,
            ProspectResearch research,
            ClaudeApi api,
            CompanyRepository companies,
            ContactRepository contacts,
            AppUserRepository users,
            CurrentUserService currentUser,
            ActivityLogger activity,
            TrashService trash,
            FieldEncryptor encryptor,
            SyncIdentity identity,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.settings = settings;
        this.research = research;
        this.api = api;
        this.companies = companies;
        this.contacts = contacts;
        this.users = users;
        this.currentUser = currentUser;
        this.activity = activity;
        this.trash = trash;
        this.encryptor = encryptor;
        this.identity = identity;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // Reading ---------------------------------------------------------------------

    public Status status() {
        AppUser me = currentUser.get();
        Values values = settings.values();
        Integer computer = values.computer();
        return new Status(
                values.hasKey(),
                values.keyHint(),
                values.enabled(),
                values.perRun(),
                values.focus(),
                values.dailyLimitDollars(),
                settings.spentCents(today()),
                values.monthlyLimitDollars(),
                settings.spentCents(month()),
                computer == null ? null : computerName(computer),
                computer != null && computer == identity.nodeId(),
                isRunning(),
                lastRun(),
                waiting(),
                me.isAdmin(),
                currentUser.isAccountOwnerOrStandIn(me),
                currentUser.accountOwnerName());
    }

    public Summary summary() {
        return new Summary(waiting(), isRunning(), lastRun());
    }

    /** The companies waiting for review, newest first, with their people. */
    @Transactional(readOnly = true)
    public List<FoundCompany> found() {
        List<Company> waiting = companies.findAllWithOwner().stream()
                .filter(company -> company.getStatus() == CompanyStatus.AI_FOUND)
                .sorted(Comparator.comparing(Company::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(company -> company.getName().toLowerCase(Locale.ROOT)))
                .toList();
        Set<Long> ids = waiting.stream().map(Company::getId).collect(Collectors.toSet());
        Map<Long, List<FoundContact>> people = contacts.findAllWithDetails().stream()
                .filter(contact -> ids.contains(contact.getCompany().getId()))
                .sorted(Comparator.comparing(Contact::getId))
                .collect(Collectors.groupingBy(contact -> contact.getCompany().getId(),
                        Collectors.mapping(contact -> new FoundContact(contact.getId(), contact.getName(),
                                contact.getTitle(), contact.getLinkedinUrl(), contact.getEmail(), contact.getPhone()),
                                Collectors.toList())));
        return waiting.stream()
                .map(company -> new FoundCompany(
                        company.getId(),
                        company.getName(),
                        company.getWebsite(),
                        company.getLocation(),
                        company.getFocus(),
                        company.getIndustry(),
                        EnumCsv.split(company.getSegments(), CompanyEnums.Segment.class),
                        company.getHiringNeed(),
                        company.getNotes(),
                        company.getCreatedAt(),
                        people.getOrDefault(company.getId(), List.of())))
                .toList();
    }

    private long waiting() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM company WHERE status = 'AI_FOUND'", Long.class);
        return count == null ? 0 : count;
    }

    private boolean isRunning() {
        if (running.get()) {
            return true;
        }
        Run last = settings.lastRun();
        return last != null && last.running() && !isStale(last);
    }

    private boolean isStale(Run run) {
        return run.started().plus(STALE).isBefore(clock.instant());
    }

    private LastRun lastRun() {
        Run run = settings.lastRun();
        if (run == null) {
            return null;
        }
        boolean stopped = run.running() && !running.get() && isStale(run);
        return new LastRun(
                stopped ? "FAILED" : run.status(),
                run.trigger(),
                run.started(),
                run.finished(),
                run.computer() == null ? null : computerName(run.computer()),
                run.startedBy(),
                run.found(),
                run.added(),
                run.skipped(),
                run.contacts(),
                run.withheld(),
                run.searches(),
                run.costCents(),
                stopped ? "The search stopped before it finished (the CRM was closed or locked)." : run.message());
    }

    private String computerName(int computer) {
        List<String> names = jdbc.queryForList("SELECT name FROM sync_node WHERE id = ?", String.class, computer);
        if (!names.isEmpty() && names.getFirst() != null && !names.getFirst().isBlank()) {
            return names.getFirst();
        }
        return computer == identity.nodeId() ? "This computer" : "Computer " + computer;
    }

    private YearMonth month() {
        return YearMonth.now(clock);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    // Settings: the key and the limits are the account owner's; the rest, any admin's ------

    /** Checks the key with the API before saving it, so a typo shows up now rather than at 7 AM. */
    public Status saveKey(KeyRequest request) {
        currentUser.requireAccountOwner("change the Claude API key");
        String key = request.apiKey().trim();
        if (!key.startsWith("sk-") || key.contains(" ")) {
            throw new BadRequestException("That doesn't look like a Claude API key. Keys start with sk-ant-.");
        }
        ClaudeApi.Reply reply;
        try {
            reply = api.models(key);
        } catch (IOException unreachable) {
            throw new BadRequestException("Couldn't reach the Claude API to check the key. "
                    + "Check the internet connection and try again.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("The key check was interrupted. Try again.");
        }
        if (reply.status() == 401) {
            throw new BadRequestException("Claude didn't accept this key. Copy it again from the Claude Console.");
        }
        if (reply.status() == 403) {
            throw new BadRequestException("This key isn't allowed to use the Claude API.");
        }
        settings.saveKey(key);
        if (settings.values().computer() == null) {
            settings.saveComputer(identity.nodeId());
        }
        return status();
    }

    public Status removeKey() {
        currentUser.requireAccountOwner("remove the Claude API key");
        settings.saveKey(null);
        return status();
    }

    public Status saveSettings(SettingsRequest request) {
        currentUser.requireAdmin();
        settings.saveSchedule(request.enabled(), request.perRun(), Text.clean(request.focus()));
        if (request.enabled() && settings.values().computer() == null) {
            settings.saveComputer(identity.nodeId());
        }
        return status();
    }

    public Status saveLimits(LimitsRequest request) {
        currentUser.requireAccountOwner("change the spending limits");
        settings.saveLimits(request.dailyLimitDollars(), request.monthlyLimitDollars());
        return status();
    }

    /** Makes this computer the one that runs the finder each weekday. */
    public Status runHere() {
        currentUser.requireAdmin();
        settings.saveComputer(identity.nodeId());
        return status();
    }

    // Runs ----------------------------------------------------------------------------

    public Status runNow() {
        start("MANUAL", currentUser.get());
        return status();
    }

    /** Every five minutes: starts today's run on the chosen computer, once the CRM is unlocked. */
    @Scheduled(initialDelayString = "${app.prospects.initial-delay-ms:180000}",
            fixedDelayString = "${app.prospects.interval-ms:300000}")
    public void tick() {
        if (!encryptor.isConfigured() || running.get()) {
            return;
        }
        try {
            if (isDue(settings.values(), settings.lastRun(), ZonedDateTime.now(clock))) {
                start("SCHEDULED", null);
            }
        } catch (RuntimeException problem) {
            log.warn("Prospect finder: could not start today's run", problem);
        }
    }

    /** Whether this computer should start today's run now. */
    boolean isDue(Values values, Run last, ZonedDateTime now) {
        if (!values.enabled() || !values.hasKey() || values.computer() == null
                || values.computer() != identity.nodeId()) {
            return false;
        }
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY || now.toLocalTime().isBefore(EARLIEST)) {
            return false;
        }
        // One run a day, including one someone started by hand.
        if (last != null && LocalDate.ofInstant(last.started(), now.getZone()).equals(now.toLocalDate())) {
            return false;
        }
        return budget(values, now.toLocalDate()).blocked() == null;
    }

    Budget budget(Values values, LocalDate day) {
        return budget(values.dailyLimitDollars(), settings.spentCents(day),
                values.monthlyLimitDollars(), settings.spentCents(YearMonth.from(day)));
    }

    static Budget budget(int dailyLimitDollars, int spentToday, int monthlyLimitDollars, int spentThisMonth) {
        int leftToday = dailyLimitDollars * 100 - spentToday;
        int leftThisMonth = monthlyLimitDollars * 100 - spentThisMonth;
        boolean daily = leftToday <= leftThisMonth;
        int cents = Math.min(leftToday, leftThisMonth);
        String name = daily ? "today's $" + dailyLimitDollars + " limit" : "this month's $" + monthlyLimitDollars + " limit";
        String blocked = null;
        if (cents <= 0) {
            blocked = daily
                    ? "Today's limit of $" + dailyLimitDollars + " is reached. The finder can search again tomorrow."
                    : "This month's limit of $" + monthlyLimitDollars + " is reached.";
        } else if (cents < MIN_RUN_CENTS) {
            blocked = String.format(Locale.US, "Only $%.2f is left of %s, less than a search needs.", cents / 100.0, name);
        }
        return new Budget(Math.max(0, cents), name, blocked);
    }

    private synchronized void start(String trigger, AppUser by) {
        Values values = settings.values();
        if (!values.hasKey()) {
            throw new BadRequestException("Add a Claude API key first.");
        }
        if (isRunning()) {
            throw new ConflictException("The finder is already searching. New companies will show up here when it's done.");
        }
        Budget budget = budget(values, today());
        if (budget.blocked() != null) {
            throw new ConflictException(budget.blocked() + " The owner can raise the limits in the settings.");
        }
        if (!running.compareAndSet(false, true)) {
            throw new ConflictException("The finder is already searching.");
        }
        try {
            Run run = Run.started(trigger, identity.nodeId(), by == null ? null : by.getDisplayName(), clock.instant());
            settings.saveRun(run);
            Long byId = by == null ? null : by.getId();
            Thread thread = new Thread(() -> execute(run, values, byId, budget), "prospect-finder");
            thread.setDaemon(true);
            thread.start();
        } catch (RuntimeException problem) {
            running.set(false);
            throw problem;
        }
    }

    private void execute(Run run, Values values, Long byId, Budget budget) {
        Usage usage = Usage.NONE;
        try {
            List<String> exclude = transaction.execute(status -> excludedNames());
            ProspectResearch.Request request = new ProspectResearch.Request(values.apiKey(), values.perRun(),
                    values.focus(), exclude == null ? List.of() : exclude, today(), budget.cents(), budget.limitName(),
                    settings.feedback());
            Outcome outcome = research.run(request);
            usage = outcome.usage();
            Found.Result found = outcome.found();
            Saved saved = transaction.execute(status -> save(found.companies(), byId));
            Objects.requireNonNull(saved);
            String message = found.companies().isEmpty()
                    ? (outcome.note() == null ? "No new companies this time." : outcome.note() + " It found no new companies.")
                    : outcome.note();
            finish(run, "DONE", found.companies().size(), saved, found.withheld(), usage, message);
            log.info("Prospect finder: found {}, added {} companies and {} people", found.companies().size(),
                    saved.added(), saved.contacts());
        } catch (ResearchException problem) {
            finish(run, "FAILED", 0, new Saved(0, 0, 0), 0, problem.usage, problem.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finish(run, "FAILED", 0, new Saved(0, 0, 0), 0, usage, "The search was stopped.");
        } catch (FieldEncryptor.CrmLockedException locked) {
            log.info("Prospect finder: the CRM locked before the run finished");
            finish(run, "FAILED", 0, new Saved(0, 0, 0), 0, usage,
                    "The CRM was locked before the search finished. It will try again next weekday.");
        } catch (RuntimeException problem) {
            log.warn("Prospect finder: the run failed", problem);
            finish(run, "FAILED", 0, new Saved(0, 0, 0), 0, usage, "Something went wrong while saving what it found.");
        } finally {
            running.set(false);
        }
    }

    private void finish(Run run, String status, int found, Saved saved, int withheld, Usage usage, String message) {
        try {
            settings.addSpend(LocalDate.ofInstant(run.started(), clock.getZone()), usage.costCents());
            settings.saveRun(new Run(status, run.trigger(), run.startedAt(), clock.instant().toEpochMilli(),
                    run.computer(), run.startedBy(), found, saved.added(), saved.skipped(), saved.contacts(),
                    withheld, usage.searches(), usage.costCents(), message));
        } catch (RuntimeException couldNotSave) {
            // Locked: the run shows as stopped once it's stale.
            log.warn("Prospect finder: could not save how the run went: {}", couldNotSave.toString());
        }
    }

    /** Every company in the CRM, any stage, so Claude doesn't spend searches on them. */
    private List<String> excludedNames() {
        return companies.findAll().stream()
                .map(Company::getName)
                .filter(Objects::nonNull)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(MAX_EXCLUDED_NAMES)
                .toList();
    }

    /** Adds the companies that aren't in the CRM yet, with their people. */
    Saved save(List<Found.Company> found, Long byId) {
        AppUser by = byId == null ? null : users.findById(byId).orElse(null);
        LocalDate today = LocalDate.now(clock);

        Set<String> names = new HashSet<>();
        Set<String> domains = new HashSet<>();
        for (Company company : companies.findAll()) {
            names.addAll(CompanyNames.keys(company.getName()));
            String domain = CompanyNames.domain(company.getWebsite());
            if (domain != null) {
                domains.add(domain);
            }
        }
        Set<String> profiles = new HashSet<>();
        for (Contact contact : contacts.findAll()) {
            String key = Text.linkedinKey(contact.getLinkedinUrl());
            if (key != null) {
                profiles.add(key);
            }
        }

        int added = 0;
        int skipped = 0;
        int people = 0;
        for (Found.Company prospect : found) {
            Set<String> keys = CompanyNames.keys(prospect.name());
            String domain = CompanyNames.domain(prospect.website());
            if (keys.stream().anyMatch(names::contains) || (domain != null && domains.contains(domain))
                    || companies.existsByNameHash(encryptor.blindIndex(prospect.name()))) {
                skipped++;
                continue;
            }

            Company company = new Company();
            company.setName(prospect.name());
            company.setWebsite(prospect.website());
            company.setLocation(prospect.location());
            company.setFocus(prospect.focus());
            company.setIndustry(prospect.industry());
            company.setSegments(EnumCsv.join(prospect.segments()));
            company.setHiringNeed(prospect.hiringNeed());
            company.setLeadSource(CompanyEnums.LeadSource.AI_RESEARCH);
            company.setStatus(CompanyStatus.AI_FOUND);
            company.setNotes(notes(prospect, today));
            companies.save(company);
            activity.forCompany(company, ActivityType.NOTE, "Found by the prospect finder. " + prospect.signal(), by);
            names.addAll(keys);
            if (domain != null) {
                domains.add(domain);
            }
            added++;

            for (Found.Person person : prospect.people()) {
                String profile = Text.linkedinKey(person.linkedinUrl());
                boolean known = (profile != null && profiles.contains(profile))
                        || (person.email() != null && contacts.existsByEmailHash(encryptor.blindIndex(person.email())));
                if (known) {
                    continue;
                }
                Contact contact = new Contact();
                contact.setName(person.name());
                contact.setTitle(person.title());
                contact.setBuyingRole(person.role());
                contact.setLinkedinUrl(person.linkedinUrl());
                contact.setEmail(person.email());
                contact.setPhone(person.phone());
                contact.setStatus(OutreachStatus.NEW);
                contact.setCompany(company);
                contact.setNotes("Found by the prospect finder on " + today.format(DAY) + ".");
                contacts.save(contact);
                if (profile != null) {
                    profiles.add(profile);
                }
                people++;
            }
        }
        return new Saved(added, skipped, people);
    }

    static String notes(Found.Company prospect, LocalDate today) {
        List<String> parts = new ArrayList<>();
        parts.add("Found by the prospect finder on " + today.format(DAY) + ".");
        parts.add("Why now: " + prospect.signal());
        if (prospect.focus() != null) {
            parts.add("What they do: " + prospect.focus());
        }
        if (!prospect.sources().isEmpty()) {
            parts.add("Sources:\n" + String.join("\n", prospect.sources()));
        }
        return String.join("\n\n", parts);
    }

    // Reviewing ---------------------------------------------------------------------

    /** Moves the company into the pipeline as a lead. Its people join the outreach queue. */
    @Transactional
    public Reviewed accept(Long id) {
        AppUser me = currentUser.get();
        Company company = waitingCompany(id);
        company.setStatus(CompanyStatus.LEAD);
        if (company.getOwner() == null) {
            company.setOwner(me);
        }
        activity.forCompany(company, ActivityType.STATUS_CHANGE, "Accepted from AI found: status changed to Lead", me);
        settings.addFeedback(new ProspectSettings.Feedback(company.getName(), true, company.getFocus(), null, null,
                clock.millis()));
        return new Reviewed(company.getId(), company.getName(), company.getStatus(), 0);
    }

    /**
     * Not a fit: the company moves to Not qualified, so the finder never suggests it again, and the
     * people found with it go to the Trash (untouched ones only), where they can be restored. The
     * reason goes into the finder's next searches, so it suggests fewer companies like this one.
     */
    @Transactional
    public Reviewed dismiss(Long id, DismissRequest request) {
        AppUser me = currentUser.get();
        DismissReason reason = request == null || request.reason() == null ? DismissReason.OTHER : request.reason();
        String note = request == null ? null : Text.clean(request.note());
        Company company = waitingCompany(id);
        company.setStatus(CompanyStatus.NOT_QUALIFIED);
        List<Long> untouched = jdbc.queryForList(
                "SELECT id FROM contact WHERE company_id = ? AND status = 'NEW' AND touch_count = 0", Long.class, id);
        for (Long contactId : untouched) {
            trash.capture(TrashKind.CONTACT, contactId);
            contacts.deleteContactById(contactId);
        }
        activity.forCompany(company, ActivityType.STATUS_CHANGE,
                "Dismissed from AI found (" + reason.meaning + (note == null ? "" : ": " + note)
                        + "): status changed to Not qualified", me);
        settings.addFeedback(new ProspectSettings.Feedback(company.getName(), false, null, reason, note,
                clock.millis()));
        return new Reviewed(company.getId(), company.getName(), company.getStatus(), untouched.size());
    }

    private Company waitingCompany(Long id) {
        Company company = companies.findWithOwnerById(id)
                .orElseThrow(() -> new NotFoundException("Company not found"));
        if (company.getStatus() != CompanyStatus.AI_FOUND) {
            throw new ConflictException("Someone already reviewed this company.");
        }
        return company;
    }

    /** For tests: whether a run is going on this computer. */
    boolean isRunningHere() {
        return running.get();
    }

    Instant now() {
        return clock.instant();
    }
}
