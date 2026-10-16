package com.janemichael.jmrecruitingcrm.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Reads the open roles out of each provider's public job board API. */
public final class JobPostings {

    /** One open role on a board. {@code publishedAt} is null when the board doesn't say. */
    public record Posting(String externalId, String title, String location, String url, Instant publishedAt) {
    }

    /** A board's open roles, and the company name the board gives (null if it doesn't). */
    public record Board(String companyName, List<Posting> postings) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private JobPostings() {
    }

    public static Board parse(JobBoardProvider provider, String body) throws IOException {
        JsonNode root = JSON.readTree(body);
        if (root == null) {
            throw new IOException("The job board sent nothing");
        }
        return switch (provider) {
            case GREENHOUSE -> greenhouse(root);
            case LEVER -> lever(root);
            case ASHBY -> ashby(root);
            case NONE -> throw new IllegalArgumentException("No board to read");
        };
    }

    /** GET boards-api.greenhouse.io/v1/boards/{token}/jobs: {"jobs": [{id, title, location: {name}, ...}]}. */
    static Board greenhouse(JsonNode root) throws IOException {
        JsonNode jobs = root.get("jobs");
        if (jobs == null || !jobs.isArray()) {
            throw new IOException("Greenhouse sent something other than a list of jobs");
        }
        List<Posting> postings = new ArrayList<>();
        String companyName = null;
        for (JsonNode job : jobs) {
            String id = text(job, "id");
            String title = text(job, "title");
            if (id == null || title == null) {
                continue;
            }
            if (companyName == null) {
                companyName = text(job, "company_name");
            }
            postings.add(new Posting(id, title, text(job.path("location"), "name"), text(job, "absolute_url"),
                    instant(text(job, "first_published"))));
        }
        return new Board(companyName, postings);
    }

    /** GET api.lever.co/v0/postings/{site}?mode=json: [{id, text, categories: {location}, hostedUrl, createdAt}]. */
    static Board lever(JsonNode root) throws IOException {
        if (!root.isArray()) {
            throw new IOException("Lever sent something other than a list of jobs");
        }
        List<Posting> postings = new ArrayList<>();
        for (JsonNode job : root) {
            String id = text(job, "id");
            String title = text(job, "text");
            if (id == null || title == null) {
                continue;
            }
            JsonNode created = job.get("createdAt");
            Instant publishedAt = created != null && created.canConvertToLong() && created.asLong() > 0
                    ? Instant.ofEpochMilli(created.asLong())
                    : null;
            postings.add(new Posting(id, title, text(job.path("categories"), "location"), text(job, "hostedUrl"),
                    publishedAt));
        }
        return new Board(null, postings);
    }

    /** GET api.ashbyhq.com/posting-api/job-board/{name}: {"jobs": [{id, title, location, jobUrl, publishedAt, isListed}]}. */
    static Board ashby(JsonNode root) throws IOException {
        JsonNode jobs = root.get("jobs");
        if (jobs == null || !jobs.isArray()) {
            throw new IOException("Ashby sent something other than a list of jobs");
        }
        List<Posting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            JsonNode listed = job.get("isListed");
            if (listed != null && listed.isBoolean() && !listed.asBoolean()) {
                continue;
            }
            String id = text(job, "id");
            String title = text(job, "title");
            if (id == null || title == null) {
                continue;
            }
            postings.add(new Posting(id, title, text(job, "location"), text(job, "jobUrl"),
                    instant(text(job, "publishedAt"))));
        }
        return new Board(null, postings);
    }

    /** A field as trimmed text, or null if it's missing or blank. Numbers come back as their digits. */
    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException withoutOffset) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException unreadable) {
                return null;
            }
        }
    }
}
