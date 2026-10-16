package com.janemichael.jmrecruitingcrm.signals;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalHelpersTest {

    // Which titles count -----------------------------------------------------------

    @Test
    void forwardDeployedTitlesMatchHoweverTheyAreWritten() {
        assertThat(RoleMatcher.classify("Forward Deployed Engineer")).contains(RoleTrack.FDE);
        assertThat(RoleMatcher.classify("Senior Forward-Deployed Engineer (NYC)")).contains(RoleTrack.FDE);
        assertThat(RoleMatcher.classify("FDE, Financial Services")).contains(RoleTrack.FDE);
        assertThat(RoleMatcher.classify("Deployment Engineer - Banking")).contains(RoleTrack.FDE);
        assertThat(RoleMatcher.classify("Applied AI Engineer")).contains(RoleTrack.FDE);
        assertThat(RoleMatcher.classify("Engineering Manager, Forward Deployed")).contains(RoleTrack.FDE);
    }

    @Test
    void platformTitlesMatch() {
        assertThat(RoleMatcher.classify("MLOps Engineer")).contains(RoleTrack.AI_PLATFORM);
        assertThat(RoleMatcher.classify("Staff Engineer, AI/ML Platform")).contains(RoleTrack.AI_PLATFORM);
        assertThat(RoleMatcher.classify("LLMOps Engineer")).contains(RoleTrack.AI_PLATFORM);
        assertThat(RoleMatcher.classify("Machine Learning Infrastructure Engineer")).contains(RoleTrack.AI_PLATFORM);
        assertThat(RoleMatcher.classify("Software Engineer, Inference")).contains(RoleTrack.AI_PLATFORM);
    }

    @Test
    void otherRolesDoNotMatch() {
        assertThat(RoleMatcher.classify("Account Executive, AI Native")).isEmpty();
        assertThat(RoleMatcher.classify("Product Manager, AI Platform")).isEmpty();
        assertThat(RoleMatcher.classify("Sales Engineer, Forward Deployed")).isEmpty();
        assertThat(RoleMatcher.classify("Backend Engineer")).isEmpty();
        assertThat(RoleMatcher.classify("Platform Engineer")).isEmpty();
        assertThat(RoleMatcher.classify("Recruiter, Forward Deployed Engineering")).isEmpty();
        assertThat(RoleMatcher.classify("Software Engineer, Infrastructure")).isEmpty();
        assertThat(RoleMatcher.classify("")).isEmpty();
        assertThat(RoleMatcher.classify(null)).isEmpty();
    }

    // Reading pasted links -----------------------------------------------------------

    @Test
    void greenhouseLinksGiveTheBoard() {
        assertThat(JobBoard.parse("https://job-boards.greenhouse.io/acme"))
                .contains(new JobBoard(JobBoardProvider.GREENHOUSE, "acme"));
        assertThat(JobBoard.parse("boards.greenhouse.io/acme/jobs/4461450008"))
                .contains(new JobBoard(JobBoardProvider.GREENHOUSE, "acme"));
        assertThat(JobBoard.parse("https://boards.greenhouse.io/embed/job_board?for=acme&b=https"))
                .contains(new JobBoard(JobBoardProvider.GREENHOUSE, "acme"));
        assertThat(JobBoard.parse("https://boards-api.greenhouse.io/v1/boards/acme/jobs"))
                .contains(new JobBoard(JobBoardProvider.GREENHOUSE, "acme"));
    }

    @Test
    void leverAndAshbyLinksGiveTheBoard() {
        assertThat(JobBoard.parse("https://jobs.lever.co/acme/6ed76ce8-4156-4b60-b120-403538bd66cd"))
                .contains(new JobBoard(JobBoardProvider.LEVER, "acme"));
        assertThat(JobBoard.parse("https://jobs.eu.lever.co/acme"))
                .contains(new JobBoard(JobBoardProvider.LEVER, "acme"));
        assertThat(JobBoard.parse("https://api.lever.co/v0/postings/acme?mode=json"))
                .contains(new JobBoard(JobBoardProvider.LEVER, "acme"));
        assertThat(JobBoard.parse("https://jobs.ashbyhq.com/Acme%20AI/34413f8d/application"))
                .contains(new JobBoard(JobBoardProvider.ASHBY, "Acme AI"));
        assertThat(JobBoard.parse("https://api.ashbyhq.com/posting-api/job-board/acme"))
                .contains(new JobBoard(JobBoardProvider.ASHBY, "acme"));
    }

    @Test
    void otherLinksAreNotBoards() {
        assertThat(JobBoard.parse("https://acme.com/careers")).isEmpty();
        assertThat(JobBoard.parse("https://job-boards.greenhouse.io/")).isEmpty();
        assertThat(JobBoard.parse("https://boards.greenhouse.io/embed/job_board")).isEmpty();
        assertThat(JobBoard.parse("   ")).isEmpty();
        assertThat(JobBoard.parse(null)).isEmpty();
    }

    @Test
    void boardsLinkToTheirCareersPage() {
        assertThat(new JobBoard(JobBoardProvider.GREENHOUSE, "acme").publicUrl())
                .isEqualTo("https://job-boards.greenhouse.io/acme");
        assertThat(new JobBoard(JobBoardProvider.ASHBY, "Acme AI").publicUrl())
                .isEqualTo("https://jobs.ashbyhq.com/Acme%20AI");
        assertThatThrownBy(() -> new JobBoard(JobBoardProvider.NONE, "acme"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobBoard(JobBoardProvider.LEVER, "../admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Reading each provider's API (shapes copied from the live APIs) -----------------------

    @Test
    void readsGreenhouseJobs() throws IOException {
        JobPostings.Board board = JobPostings.parse(JobBoardProvider.GREENHOUSE, """
                {"jobs":[{"absolute_url":"https://job-boards.greenhouse.io/acme/jobs/4461450008",
                  "internal_job_id":4147866008,"location":{"name":"New York City, NY"},"metadata":[],
                  "id":4461450008,"updated_at":"2026-08-21T21:32:54-04:00","requisition_id":"3356",
                  "title":"Forward Deployed Engineer","company_name":"Acme",
                  "first_published":"2024-12-20T13:53:38-05:00","language":"en"}],
                 "meta":{"total":1}}
                """);
        assertThat(board.companyName()).isEqualTo("Acme");
        assertThat(board.postings()).containsExactly(new JobPostings.Posting("4461450008",
                "Forward Deployed Engineer", "New York City, NY",
                "https://job-boards.greenhouse.io/acme/jobs/4461450008", Instant.parse("2024-12-20T18:53:38Z")));
    }

    @Test
    void readsLeverPostings() throws IOException {
        JobPostings.Board board = JobPostings.parse(JobBoardProvider.LEVER, """
                [{"categories":{"commitment":"Full-time","location":"London","team":"Engineering"},
                  "createdAt":1786469891368,"id":"6ed76ce8-4156-4b60-b120-403538bd66cd",
                  "text":"MLOps Engineer","country":"GB","workplaceType":"hybrid",
                  "hostedUrl":"https://jobs.lever.co/acme/6ed76ce8-4156-4b60-b120-403538bd66cd",
                  "applyUrl":"https://jobs.lever.co/acme/6ed76ce8-4156-4b60-b120-403538bd66cd/apply"}]
                """);
        assertThat(board.postings()).containsExactly(new JobPostings.Posting(
                "6ed76ce8-4156-4b60-b120-403538bd66cd", "MLOps Engineer", "London",
                "https://jobs.lever.co/acme/6ed76ce8-4156-4b60-b120-403538bd66cd", Instant.ofEpochMilli(1786469891368L)));
    }

    @Test
    void readsAshbyJobsAndSkipsUnlistedOnes() throws IOException {
        JobPostings.Board board = JobPostings.parse(JobBoardProvider.ASHBY, """
                {"apiVersion":"1","jobs":[
                  {"id":"34413f8d-26bf-4bbc-8ade-eb309a0e2245","title":" Forward Deployed Engineer",
                   "department":"Engineering","team":"Deployments","employmentType":"FullTime",
                   "location":"New York, NY (HQ)","secondaryLocations":[],"publishedAt":"2026-04-07T17:12:35.753+00:00",
                   "isListed":true,"isRemote":true,"workplaceType":"Hybrid",
                   "jobUrl":"https://jobs.ashbyhq.com/acme/34413f8d-26bf-4bbc-8ade-eb309a0e2245",
                   "applyUrl":"https://jobs.ashbyhq.com/acme/34413f8d-26bf-4bbc-8ade-eb309a0e2245/application",
                   "descriptionPlain":"About Acme"},
                  {"id":"hidden","title":"MLOps Engineer","isListed":false,"publishedAt":"2026-04-07T17:12:35Z"}]}
                """);
        assertThat(board.postings()).containsExactly(new JobPostings.Posting(
                "34413f8d-26bf-4bbc-8ade-eb309a0e2245", "Forward Deployed Engineer", "New York, NY (HQ)",
                "https://jobs.ashbyhq.com/acme/34413f8d-26bf-4bbc-8ade-eb309a0e2245",
                Instant.parse("2026-04-07T17:12:35.753Z")));
    }

    @Test
    void anUnexpectedAnswerIsAnError() {
        assertThatThrownBy(() -> JobPostings.parse(JobBoardProvider.GREENHOUSE, "{\"status\":404}"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> JobPostings.parse(JobBoardProvider.LEVER, "{\"ok\":false}"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> JobPostings.parse(JobBoardProvider.ASHBY, "not json"))
                .isInstanceOf(IOException.class);
    }

    // Guessing boards ---------------------------------------------------------------

    @Test
    void boardNamesComeFromTheWebsiteThenTheName() {
        assertThat(BoardFinder.names("Acme AI, Inc.", "https://www.acme.ai/about"))
                .containsExactly("acme", "acmeai", "acme-ai");
        assertThat(BoardFinder.names("Get Acme", "getacme.com")).containsExactly("getacme", "get-acme");
        assertThat(BoardFinder.names("Acme Risk", "acme-risk.co.uk"))
                .containsExactly("acme-risk", "acmerisk");
        assertThat(BoardFinder.names("Zest AI", null)).containsExactly("zestai", "zest-ai", "zest");
        assertThat(BoardFinder.names("", "")).isEmpty();
    }

    @Test
    void greenhouseBoardsMustNameTheCompany() {
        assertThat(BoardFinder.sameCompany("Acme AI", "Acme")).isTrue();
        assertThat(BoardFinder.sameCompany("Acme, Inc.", "ACME")).isTrue();
        assertThat(BoardFinder.sameCompany("Acme Technologies", "Acme Labs")).isTrue();
        assertThat(BoardFinder.sameCompany("Acme", "Acme Health")).isFalse();
        assertThat(BoardFinder.sameCompany("Acme", null)).isFalse();
    }

    // What counts as new ---------------------------------------------------------------

    @Test
    void rolesAreNewUntilReviewedForThirtyDays() {
        Instant now = Instant.parse("2026-10-15T12:00:00Z");
        Instant lastWeek = now.minus(Duration.ofDays(7));
        assertThat(SignalService.unreviewed(lastWeek, null, now)).isTrue();
        assertThat(SignalService.unreviewed(lastWeek, now.minus(Duration.ofDays(8)), now)).isTrue();
        assertThat(SignalService.unreviewed(lastWeek, now.minus(Duration.ofDays(1)), now)).isFalse();
        assertThat(SignalService.unreviewed(now.minus(Duration.ofDays(45)), null, now)).isFalse();
        assertThat(SignalService.unreviewed(null, null, now)).isFalse();
        assertThat(Optional.ofNullable(SignalService.label(JobBoardProvider.ASHBY))).contains("Ashby");
    }
}
