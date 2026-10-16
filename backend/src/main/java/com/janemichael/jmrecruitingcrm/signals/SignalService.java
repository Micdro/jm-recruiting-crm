package com.janemichael.jmrecruitingcrm.signals;

import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.company.CompanyStatus;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.contact.OutreachStatus;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.BoardRequest;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.CompanyBoard;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.CompanyRef;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.CompanySignal;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.Job;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.JobStarted;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.Overview;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.Person;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.Role;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.SignalsResponse;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.Summary;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.SummaryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Hiring signals: forward deployed and AI platform roles open at companies in the CRM, read from
 * their public Greenhouse, Lever and Ashby job boards.
 *
 * <ul>
 *   <li>Each computer reads the boards itself every few hours while the CRM is unlocked, and keeps
 *       the matching roles in a local, encrypted cache. Only which board a company uses, and when the
 *       team reviewed its signals, is shared.</li>
 *   <li>A role is new until the team marks the company reviewed, for 30 days after it was posted.</li>
 *   <li>The finder guesses boards from each company's website and name. Boards it finds wait for
 *       someone to confirm them.</li>
 *   <li>The finder also runs on its own: every 15 minutes it looks up a batch of companies with no
 *       board on file that it hasn't looked up in the last 30 days, so new companies (accepted from
 *       AI Found, imported or added by hand) are covered without anyone clicking Find job boards.</li>
 *   <li>Companies waiting on AI Found aren't looked up until someone accepts them.</li>
 * </ul>
 */
@Service
public class SignalService {

    private static final Logger log = LoggerFactory.getLogger(SignalService.class);

    /** How often each board is read again. */
    static final Duration CHECK_EVERY = Duration.ofHours(6);
    /** How long a role counts as new. */
    static final Duration NEW_FOR = Duration.ofDays(30);
    /** How long before the finder looks again for a company whose board it couldn't find. */
    static final Duration SEARCH_AGAIN_AFTER = Duration.ofDays(30);
    static final int PARALLEL = 6;
    static final int PEOPLE_SHOWN = 3;
    static final int SUMMARY_SIZE = 5;
    /** Companies the automatic lookup takes on per run, so a long list is spread over a few runs. */
    static final int AUTO_FIND_BATCH = 40;
    /** Not looked up at all until someone accepts them on AI Found. */
    static final Set<CompanyStatus> NOT_REVIEWED = EnumSet.of(CompanyStatus.AI_FOUND);
    /** Not looked up automatically: not reviewed yet, or not a fit. Find job boards still covers the last two. */
    static final Set<CompanyStatus> SKIP_AUTO_FIND =
            EnumSet.of(CompanyStatus.AI_FOUND, CompanyStatus.NOT_QUALIFIED, CompanyStatus.NOT_INTERESTED);

    private static final String READABLE = "('GREENHOUSE', 'LEVER', 'ASHBY')";

    /** A company to read or look for a board for. {@code board} is null when looking. */
    record Target(long companyId, String name, String website, JobBoard board) {
    }

    /** A role in the local cache, decrypted. */
    record Stored(long companyId, JobBoardProvider provider, String title, String location, String url,
                  RoleTrack track, Instant publishedAt, Instant firstSeenAt) {

        Instant posted() {
            return publishedAt != null ? publishedAt : firstSeenAt;
        }
    }

    /** This computer's last read of a company's board. */
    record Check(Instant checkedAt, Integer openRoles, String error, Instant searchedAt) {
        static final Check NONE = new Check(null, null, null, null);
    }

    private static final class RunningJob {
        final String kind;
        final int total;
        final AtomicInteger done = new AtomicInteger();

        RunningJob(String kind, int total) {
            this.kind = kind;
            this.total = total;
        }

        Job view() {
            return new Job(kind, done.get(), total);
        }
    }

    private final JdbcTemplate jdbc;
    private final CompanyRepository companies;
    private final ContactRepository contacts;
    private final JobBoardClient client;
    private final FieldEncryptor encryptor;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AtomicReference<RunningJob> running = new AtomicReference<>();

    public SignalService(
            JdbcTemplate jdbc,
            CompanyRepository companies,
            ContactRepository contacts,
            JobBoardClient client,
            FieldEncryptor encryptor,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.companies = companies;
        this.contacts = contacts;
        this.client = client;
        this.encryptor = encryptor;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // Reading ---------------------------------------------------------------------

    public Overview overview() {
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT
                  count(*) FILTER (WHERE job_board_provider IN %1$s) AS with_board,
                  count(*) FILTER (WHERE job_board_provider IS NULL AND status <> 'AI_FOUND') AS without_board,
                  count(*) FILTER (WHERE job_board_provider = 'NONE') AS no_board,
                  count(*) FILTER (WHERE job_board_provider IN %1$s AND NOT job_board_confirmed) AS to_confirm
                FROM company
                """.formatted(READABLE));
        Integer failing = jdbc.queryForObject("""
                SELECT count(*) FROM job_board_check k JOIN company c ON c.id = k.company_id
                WHERE k.error IS NOT NULL AND c.job_board_provider IN %s
                """.formatted(READABLE), Integer.class);
        Timestamp lastChecked = jdbc.queryForObject("SELECT max(checked_at) FROM job_board_check", Timestamp.class);
        RunningJob job = running.get();
        return new Overview(
                job == null ? null : job.view(),
                lastChecked == null ? null : lastChecked.toInstant(),
                number(counts.get("with_board")),
                number(counts.get("without_board")),
                number(counts.get("no_board")),
                number(counts.get("to_confirm")),
                failing == null ? 0 : failing);
    }

    public SignalsResponse list() {
        Instant now = clock.instant();
        List<Company> all = companies.findAllWithOwner();
        Map<Long, Check> checks = checks();
        List<CompanySignal> signals = signals(all, checks, now, true);

        List<CompanyRef> withoutBoard = new ArrayList<>();
        List<CompanyRef> failing = new ArrayList<>();
        for (Company company : all) {
            Check check = checks.getOrDefault(company.getId(), Check.NONE);
            JobBoard board = boardOf(company);
            if (company.getJobBoardProvider() == null && !NOT_REVIEWED.contains(company.getStatus())) {
                withoutBoard.add(new CompanyRef(company.getId(), company.getName(), company.getStatus(), null, null,
                        check.searchedAt(), null));
            } else if (board != null && check.error() != null) {
                failing.add(new CompanyRef(company.getId(), company.getName(), company.getStatus(),
                        board.provider(), board.publicUrl(), check.searchedAt(), check.error()));
            }
        }
        Comparator<CompanyRef> byName = Comparator.comparing(ref -> ref.name().toLowerCase());
        withoutBoard.sort(byName);
        failing.sort(byName);
        return new SignalsResponse(overview(), signals, withoutBoard, failing);
    }

    /** Companies with roles nobody has reviewed yet, for Today. */
    public Summary summary() {
        List<CompanySignal> fresh = signals(companies.findAllWithOwner(), Map.of(), clock.instant(), false).stream()
                .filter(signal -> signal.unreviewedRoles() > 0)
                .toList();
        List<SummaryItem> top = fresh.stream()
                .limit(SUMMARY_SIZE)
                .map(signal -> new SummaryItem(signal.companyId(), signal.name(), signal.status(),
                        signal.unreviewedRoles(),
                        signal.roles().stream().filter(Role::unreviewed).map(Role::title).findFirst().orElse(null)))
                .toList();
        RunningJob job = running.get();
        return new Summary(fresh.size(), top, job == null ? null : job.view());
    }

    public CompanyBoard companyBoard(long companyId) {
        Company company = companies.findById(companyId).orElseThrow(() -> new NotFoundException("Company not found"));
        Check check = checks().getOrDefault(companyId, Check.NONE);
        JobBoard board = boardOf(company);
        Instant now = clock.instant();
        List<Role> roles = board == null ? List.of() : roles(stored(companyId), board.provider(),
                company.getSignalsReviewedAt(), now);
        return new CompanyBoard(companyId, company.getJobBoardProvider(), company.getJobBoardToken(),
                board == null ? null : board.publicUrl(), company.isJobBoardConfirmed(),
                check.checkedAt(), check.openRoles(), check.error(), check.searchedAt(),
                company.getSignalsReviewedAt(), roles);
    }

    private List<CompanySignal> signals(List<Company> all, Map<Long, Check> checks, Instant now, boolean withPeople) {
        Map<Long, List<Stored>> byCompany = new HashMap<>();
        for (Stored role : stored(null)) {
            byCompany.computeIfAbsent(role.companyId(), id -> new ArrayList<>()).add(role);
        }

        List<Company> hiring = new ArrayList<>();
        for (Company company : all) {
            JobBoard board = boardOf(company);
            if (board != null && byCompany.containsKey(company.getId())) {
                hiring.add(company);
            }
        }
        Map<Long, List<Contact>> people = withPeople ? people(hiring) : Map.of();

        List<CompanySignal> signals = new ArrayList<>();
        for (Company company : hiring) {
            JobBoard board = boardOf(company);
            List<Role> roles = roles(byCompany.get(company.getId()), board.provider(), company.getSignalsReviewedAt(), now);
            if (roles.isEmpty()) {
                continue;
            }
            List<Contact> atCompany = people.getOrDefault(company.getId(), List.of());
            List<Person> shown = atCompany.stream()
                    .filter(contact -> contact.getStatus() != OutreachStatus.DO_NOT_CONTACT
                            && contact.getStatus() != OutreachStatus.NOT_INTERESTED)
                    .sorted(Comparator.comparing(Contact::getNextActionDate, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(contact -> contact.getName().toLowerCase()))
                    .limit(PEOPLE_SHOWN)
                    .map(contact -> new Person(contact.getId(), contact.getName(), contact.getTitle(),
                            contact.getStatus(), contact.getNextActionDate()))
                    .toList();
            Check check = checks.getOrDefault(company.getId(), Check.NONE);
            int unreviewed = (int) roles.stream().filter(Role::unreviewed).count();
            Instant latest = roles.stream().map(Role::publishedAt).filter(at -> at != null)
                    .max(Comparator.naturalOrder()).orElse(null);
            signals.add(new CompanySignal(
                    company.getId(),
                    company.getName(),
                    company.getStatus(),
                    company.getTier(),
                    company.getPriority(),
                    company.getOwner() == null ? null : company.getOwner().getDisplayName(),
                    board.provider(),
                    board.publicUrl(),
                    company.isJobBoardConfirmed(),
                    company.getSignalsReviewedAt(),
                    check.openRoles(),
                    unreviewed,
                    latest,
                    roles,
                    shown,
                    atCompany.size()));
        }

        signals.sort(Comparator
                .comparing((CompanySignal signal) -> signal.unreviewedRoles() == 0)
                .thenComparing(CompanySignal::latestPublishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(signal -> signal.name().toLowerCase()));
        return signals;
    }

    /** The cached roles from the company's current board, newest first. */
    static List<Role> roles(List<Stored> stored, JobBoardProvider provider, Instant reviewedAt, Instant now) {
        if (stored == null) {
            return List.of();
        }
        return stored.stream()
                .filter(role -> role.provider() == provider)
                .sorted(Comparator.comparing(Stored::posted, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(role -> new Role(role.title(), role.location(), role.url(), role.track(), role.posted(),
                        unreviewed(role.posted(), reviewedAt, now)))
                .toList();
    }

    /** Posted in the last 30 days, and since the team last reviewed the company. */
    static boolean unreviewed(Instant posted, Instant reviewedAt, Instant now) {
        if (posted == null || posted.isBefore(now.minus(NEW_FOR))) {
            return false;
        }
        return reviewedAt == null || posted.isAfter(reviewedAt);
    }

    private Map<Long, List<Contact>> people(List<Company> hiring) {
        if (hiring.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = new HashSet<>();
        hiring.forEach(company -> ids.add(company.getId()));
        Map<Long, List<Contact>> byCompany = new HashMap<>();
        for (Contact contact : contacts.findByCompanyIds(ids)) {
            byCompany.computeIfAbsent(contact.getCompany().getId(), id -> new ArrayList<>()).add(contact);
        }
        return byCompany;
    }

    /** The cached roles, for one company or (with null) all of them. */
    private List<Stored> stored(Long companyId) {
        String sql = """
                SELECT company_id, provider, title, location, url, track, published_at, first_seen_at
                FROM job_posting
                """ + (companyId == null ? "" : " WHERE company_id = ?");
        Object[] params = companyId == null ? new Object[0] : new Object[]{companyId};
        return jdbc.query(sql, (resultSet, row) -> new Stored(
                resultSet.getLong("company_id"),
                JobBoardProvider.valueOf(resultSet.getString("provider")),
                encryptor.decrypt(resultSet.getString("title")),
                decrypt(resultSet.getString("location")),
                decrypt(resultSet.getString("url")),
                RoleTrack.valueOf(resultSet.getString("track")),
                instant(resultSet, "published_at"),
                instant(resultSet, "first_seen_at")), params);
    }

    private Map<Long, Check> checks() {
        Map<Long, Check> checks = new HashMap<>();
        jdbc.query("SELECT company_id, checked_at, open_roles, error, searched_at FROM job_board_check", resultSet -> {
            Object openRoles = resultSet.getObject("open_roles");
            checks.put(resultSet.getLong("company_id"), new Check(
                    instant(resultSet, "checked_at"),
                    openRoles == null ? null : ((Number) openRoles).intValue(),
                    resultSet.getString("error"),
                    instant(resultSet, "searched_at")));
        });
        return checks;
    }

    // Changing a company's board ----------------------------------------------------

    public CompanyBoard setBoard(long companyId, BoardRequest request) {
        companies.findById(companyId).orElseThrow(() -> new NotFoundException("Company not found"));

        if (request.noBoard()) {
            saveBoard(companyId, JobBoardProvider.NONE, null, true);
            forget(companyId, false);
            return companyBoard(companyId);
        }
        String link = Text.clean(request.link());
        if (link == null) {
            // Cleared: the finder may look for it again.
            saveBoard(companyId, null, null, false);
            forget(companyId, true);
            return companyBoard(companyId);
        }

        JobBoard board = JobBoard.parse(link).orElseThrow(() -> new BadRequestException(
                "Paste a link to the company's Greenhouse, Lever or Ashby job board, like "
                        + "job-boards.greenhouse.io/acme, jobs.lever.co/acme or jobs.ashbyhq.com/acme."));
        JobPostings.Board contents = null;
        String problem = null;
        try {
            contents = client.fetch(board);
        } catch (JobBoardClient.BoardNotFoundException notThere) {
            throw new BadRequestException("There's no " + label(board.provider()) + " job board called \""
                    + board.token() + "\". Check the link and try again.");
        } catch (IOException unreachable) {
            problem = "Couldn't read the board yet: " + unreachable.getMessage();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            problem = "Couldn't read the board yet. It's read again in a few hours.";
        }

        saveBoard(companyId, board.provider(), board.token(), true);
        if (contents != null) {
            store(companyId, board.provider(), contents.postings());
        } else {
            recordProblem(companyId, problem, false);
        }
        return companyBoard(companyId);
    }

    /** Someone checked a board the finder found: it's the right one. */
    public CompanyBoard confirm(long companyId) {
        transaction.executeWithoutResult(status -> {
            Company company = companies.findById(companyId).orElseThrow(() -> new NotFoundException("Company not found"));
            if (boardOf(company) == null) {
                throw new BadRequestException("This company has no job board to confirm");
            }
            company.setJobBoardConfirmed(true);
        });
        return companyBoard(companyId);
    }

    /** The team has gone through the company's open roles. Roles posted after this are new again. */
    public void markReviewed(long companyId) {
        transaction.executeWithoutResult(status -> companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found"))
                .setSignalsReviewedAt(clock.instant()));
    }

    /** Reads one company's board now. */
    public CompanyBoard checkNow(long companyId) {
        Company company = companies.findById(companyId).orElseThrow(() -> new NotFoundException("Company not found"));
        JobBoard board = boardOf(company);
        if (board == null) {
            throw new BadRequestException("Add the company's job board first");
        }
        check(new Target(companyId, company.getName(), company.getWebsite(), board));
        return companyBoard(companyId);
    }

    private void saveBoard(long companyId, JobBoardProvider provider, String token, boolean confirmed) {
        transaction.executeWithoutResult(status -> companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found"))
                .setJobBoard(provider, token, confirmed));
    }

    /** Drops the cached roles. {@code searchAgain} also lets the finder look for the company's board again. */
    private void forget(long companyId, boolean searchAgain) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM job_posting WHERE company_id = ?", companyId);
            if (searchAgain) {
                jdbc.update("DELETE FROM job_board_check WHERE company_id = ?", companyId);
            } else {
                jdbc.update("UPDATE job_board_check SET open_roles = NULL, error = NULL WHERE company_id = ?",
                        companyId);
            }
        });
    }

    // Jobs: reading boards and finding them -------------------------------------------

    /**
     * Every 15 minutes: reads any board this computer hasn't read in the last six hours, or else looks
     * up boards for a batch of companies that have none on file.
     */
    @Scheduled(initialDelayString = "${app.signals.initial-delay-ms:120000}",
            fixedDelayString = "${app.signals.interval-ms:900000}")
    public void scheduledCheck() {
        if (!encryptor.isConfigured() || running.get() != null) {
            return;
        }
        try {
            Integer due = jdbc.queryForObject("""
                    SELECT count(*) FROM company c LEFT JOIN job_board_check k ON k.company_id = c.id
                    WHERE c.job_board_provider IN %s AND (k.checked_at IS NULL OR k.checked_at < ?)
                    """.formatted(READABLE), Integer.class, Timestamp.from(clock.instant().minus(CHECK_EVERY)));
            if (due != null && due > 0) {
                startCheck(false);
                return;
            }
            List<Target> toFind = findTargets(true);
            if (!toFind.isEmpty()) {
                start("FIND", toFind, this::find);
            }
        } catch (RuntimeException problem) {
            log.warn("Could not start reading or finding job boards", problem);
        }
    }

    /** Reads the boards in the background: every one, or only those not read in the last six hours. */
    public JobStarted startCheck(boolean all) {
        Instant cutoff = clock.instant().minus(CHECK_EVERY);
        Map<Long, Check> checks = checks();
        List<Target> targets = new ArrayList<>();
        for (Company company : companies.findAll()) {
            JobBoard board = boardOf(company);
            Instant checkedAt = checks.getOrDefault(company.getId(), Check.NONE).checkedAt();
            if (board != null && (all || checkedAt == null || checkedAt.isBefore(cutoff))) {
                targets.add(new Target(company.getId(), company.getName(), company.getWebsite(), board));
            }
        }
        return start("CHECK", targets, this::check);
    }

    /** Looks in the background for the boards of companies that don't have one on file. */
    public JobStarted startFind() {
        return start("FIND", findTargets(false), this::find);
    }

    /**
     * Companies with no board on file that this computer hasn't looked up in the last 30 days.
     * Automatic runs skip companies that aren't a fit, and take a batch: never looked up first, then
     * the longest ago.
     */
    List<Target> findTargets(boolean automatic) {
        Instant cutoff = clock.instant().minus(SEARCH_AGAIN_AFTER);
        Map<Long, Check> checks = checks();
        Set<CompanyStatus> skip = automatic ? SKIP_AUTO_FIND : NOT_REVIEWED;
        List<Company> due = new ArrayList<>();
        for (Company company : companies.findAll()) {
            Instant searchedAt = checks.getOrDefault(company.getId(), Check.NONE).searchedAt();
            if (company.getJobBoardProvider() == null && !skip.contains(company.getStatus())
                    && (searchedAt == null || searchedAt.isBefore(cutoff))) {
                due.add(company);
            }
        }
        if (automatic) {
            due.sort(Comparator.comparing(
                    (Company company) -> checks.getOrDefault(company.getId(), Check.NONE).searchedAt(),
                    Comparator.nullsFirst(Comparator.naturalOrder())));
        }
        return due.stream()
                .limit(automatic ? AUTO_FIND_BATCH : Long.MAX_VALUE)
                .map(company -> new Target(company.getId(), company.getName(), company.getWebsite(), null))
                .toList();
    }

    private JobStarted start(String kind, List<Target> targets, Consumer<Target> work) {
        RunningJob job = new RunningJob(kind, targets.size());
        if (targets.isEmpty() || !running.compareAndSet(null, job)) {
            return new JobStarted(false, targets.size(), overview());
        }
        Thread thread = new Thread(() -> run(job, targets, work), "hiring-signals-" + kind.toLowerCase());
        thread.setDaemon(true);
        thread.start();
        return new JobStarted(true, targets.size(), overview());
    }

    private void run(RunningJob job, List<Target> targets, Consumer<Target> work) {
        try (ExecutorService pool = Executors.newFixedThreadPool(PARALLEL)) {
            for (Target target : targets) {
                pool.submit(() -> {
                    try {
                        // Signing out locks the CRM; what's left waits for the next run.
                        if (encryptor.isConfigured()) {
                            work.accept(target);
                        }
                    } catch (RuntimeException problem) {
                        log.warn("Hiring signals: company {} failed: {}", target.companyId(), problem.toString());
                    } finally {
                        job.done.incrementAndGet();
                    }
                });
            }
        } finally {
            running.compareAndSet(job, null);
            log.info("Hiring signals: {} finished for {} companies", job.kind.toLowerCase(), job.total);
        }
    }

    /** Reads one board and updates the cache. */
    void check(Target target) {
        JobBoard board = target.board();
        try {
            store(target.companyId(), board.provider(), client.fetch(board).postings());
        } catch (JobBoardClient.BoardNotFoundException gone) {
            recordProblem(target.companyId(), "The " + label(board.provider()) + " board \"" + board.token()
                    + "\" wasn't found. It may have moved: add the new link on the company's page.", true);
        } catch (IOException problem) {
            recordProblem(target.companyId(), "Couldn't read the board: " + problem.getMessage(), false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Tries the likely board names at each provider and keeps the first board that's there. */
    void find(Target target) {
        try {
            for (String name : BoardFinder.names(target.name(), target.website())) {
                for (JobBoardProvider provider : BoardFinder.PROVIDERS) {
                    JobBoard board;
                    try {
                        board = new JobBoard(provider, name);
                    } catch (IllegalArgumentException notAName) {
                        continue;
                    }
                    JobPostings.Board contents;
                    try {
                        contents = client.fetch(board);
                    } catch (JobBoardClient.BoardNotFoundException notThere) {
                        continue;
                    }
                    if (provider == JobBoardProvider.GREENHOUSE && !greenhouseMatches(target, board, contents)) {
                        continue;
                    }
                    Boolean saved = transaction.execute(status -> {
                        Company company = companies.findById(target.companyId()).orElse(null);
                        // Someone may have set it in the meantime, here or on another computer.
                        if (company == null || company.getJobBoardProvider() != null) {
                            return false;
                        }
                        company.setJobBoard(provider, board.token(), false);
                        return true;
                    });
                    if (Boolean.TRUE.equals(saved)) {
                        store(target.companyId(), provider, contents.postings());
                    }
                    markSearched(target.companyId());
                    return;
                }
            }
            markSearched(target.companyId());
        } catch (IOException problem) {
            // Not marked as searched, so the next run tries again.
            log.info("Hiring signals: couldn't finish looking for company {}'s board: {}",
                    target.companyId(), problem.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Greenhouse boards name their company, so a board that belongs to someone else is skipped. */
    private boolean greenhouseMatches(Target target, JobBoard board, JobPostings.Board contents)
            throws IOException, InterruptedException {
        String boardName = contents.companyName();
        if (boardName == null) {
            try {
                boardName = client.greenhouseName(board);
            } catch (JobBoardClient.BoardNotFoundException notThere) {
                return false;
            }
        }
        return boardName == null || BoardFinder.sameCompany(target.name(), boardName);
    }

    /** Keeps the board's matching roles, drops the ones that are gone, and notes the read. */
    void store(long companyId, JobBoardProvider provider, List<JobPostings.Posting> postings) {
        Timestamp now = Timestamp.from(clock.instant());
        transaction.executeWithoutResult(status -> {
            Set<String> keep = new HashSet<>();
            for (JobPostings.Posting posting : postings) {
                Optional<RoleTrack> track = RoleMatcher.classify(posting.title());
                if (track.isEmpty()) {
                    continue;
                }
                String externalId = truncate(posting.externalId(), 100);
                keep.add(externalId);
                jdbc.update("""
                        INSERT INTO job_posting (company_id, provider, external_id, title, location, url, track,
                                                 published_at, first_seen_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (company_id, provider, external_id) DO UPDATE
                            SET title = EXCLUDED.title, location = EXCLUDED.location, url = EXCLUDED.url,
                                track = EXCLUDED.track, published_at = EXCLUDED.published_at
                        """, companyId, provider.name(), externalId, encryptor.encrypt(posting.title()),
                        encrypt(posting.location()), encrypt(posting.url()), track.get().name(),
                        posting.publishedAt() == null ? null : Timestamp.from(posting.publishedAt()), now);
            }

            List<Long> gone = new ArrayList<>();
            jdbc.query("SELECT id, provider, external_id FROM job_posting WHERE company_id = ?", resultSet -> {
                if (!provider.name().equals(resultSet.getString("provider"))
                        || !keep.contains(resultSet.getString("external_id"))) {
                    gone.add(resultSet.getLong("id"));
                }
            }, companyId);
            for (Long id : gone) {
                jdbc.update("DELETE FROM job_posting WHERE id = ?", id);
            }

            jdbc.update("""
                    INSERT INTO job_board_check (company_id, checked_at, open_roles, error) VALUES (?, ?, ?, NULL)
                    ON CONFLICT (company_id) DO UPDATE
                        SET checked_at = EXCLUDED.checked_at, open_roles = EXCLUDED.open_roles, error = NULL
                    """, companyId, now, postings.size());
        });
    }

    /** Notes a board that couldn't be read. {@code gone}: it no longer exists, so its roles are dropped too. */
    private void recordProblem(long companyId, String error, boolean gone) {
        Timestamp now = Timestamp.from(clock.instant());
        transaction.executeWithoutResult(status -> {
            if (gone) {
                jdbc.update("DELETE FROM job_posting WHERE company_id = ?", companyId);
            }
            jdbc.update("""
                    INSERT INTO job_board_check (company_id, checked_at, error) VALUES (?, ?, ?)
                    ON CONFLICT (company_id) DO UPDATE SET checked_at = EXCLUDED.checked_at, error = EXCLUDED.error
                    """, companyId, now, truncate(error, 500));
        });
    }

    private void markSearched(long companyId) {
        jdbc.update("""
                INSERT INTO job_board_check (company_id, searched_at) VALUES (?, ?)
                ON CONFLICT (company_id) DO UPDATE SET searched_at = EXCLUDED.searched_at
                """, companyId, Timestamp.from(clock.instant()));
    }

    // Helpers -------------------------------------------------------------------------

    /** The company's readable board, or null if it has none (or what's stored isn't a board). */
    static JobBoard boardOf(Company company) {
        JobBoardProvider provider = company.getJobBoardProvider();
        if (provider == null || !provider.isReadable() || company.getJobBoardToken() == null) {
            return null;
        }
        try {
            return new JobBoard(provider, company.getJobBoardToken());
        } catch (IllegalArgumentException notABoard) {
            return null;
        }
    }

    static String label(JobBoardProvider provider) {
        return switch (provider) {
            case GREENHOUSE -> "Greenhouse";
            case LEVER -> "Lever";
            case ASHBY -> "Ashby";
            case NONE -> "job";
        };
    }

    private String encrypt(String value) {
        return value == null ? null : encryptor.encrypt(value);
    }

    private String decrypt(String value) {
        return value == null ? null : encryptor.decrypt(value);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static int number(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static String truncate(String value, int length) {
        if (value == null) {
            return null;
        }
        return value.length() > length ? value.substring(0, length) : value;
    }
}
