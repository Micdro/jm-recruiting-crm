package com.janemichael.jmrecruitingcrm.signals;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A company's public job board: the provider and the board's name there (Greenhouse calls it the
 * board token, Lever the site name, Ashby the job board name).
 */
public record JobBoard(JobBoardProvider provider, String token) {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,99}");

    public JobBoard {
        if (provider == null || !provider.isReadable()) {
            throw new IllegalArgumentException("A job board needs Greenhouse, Lever or Ashby");
        }
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("Not a job board name: " + token);
        }
    }

    /** The careers page people see, to open from the CRM. */
    public String publicUrl() {
        String name = encoded();
        return switch (provider) {
            case GREENHOUSE -> "https://job-boards.greenhouse.io/" + name;
            case LEVER -> "https://jobs.lever.co/" + name;
            case ASHBY -> "https://jobs.ashbyhq.com/" + name;
            case NONE -> throw new IllegalStateException();
        };
    }

    /** The board's name, ready to go in a URL path. */
    String encoded() {
        return URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Reads a board from a link someone pasted: the careers page, one job on it, an embed link or
     * the API address. Empty if it isn't a Greenhouse, Lever or Ashby link.
     */
    public static Optional<JobBoard> parse(String link) {
        if (link == null || link.isBlank()) {
            return Optional.empty();
        }
        String text = link.trim();
        if (!text.matches("(?i)^https?://.*")) {
            text = "https://" + text;
        }
        URI uri;
        try {
            uri = new URI(text.replace(" ", "%20"));
        } catch (URISyntaxException unreadable) {
            return Optional.empty();
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String[] path = segments(uri.getRawPath());

        try {
            if (host.endsWith("greenhouse.io")) {
                String query = uri.getRawQuery();
                String forParam = query == null ? null : param(query, "for");
                if (forParam != null) {
                    return Optional.of(new JobBoard(JobBoardProvider.GREENHOUSE, forParam));
                }
                if (host.startsWith("boards-api.")) {
                    // /v1/boards/{token}/jobs
                    return path.length >= 3 && path[1].equals("boards")
                            ? Optional.of(new JobBoard(JobBoardProvider.GREENHOUSE, path[2]))
                            : Optional.empty();
                }
                if (path.length >= 1 && !path[0].equals("embed")) {
                    return Optional.of(new JobBoard(JobBoardProvider.GREENHOUSE, path[0]));
                }
                return Optional.empty();
            }
            if (host.endsWith("lever.co")) {
                if (host.startsWith("api.")) {
                    // /v0/postings/{site}
                    return path.length >= 3 && path[1].equals("postings")
                            ? Optional.of(new JobBoard(JobBoardProvider.LEVER, path[2]))
                            : Optional.empty();
                }
                return path.length >= 1 ? Optional.of(new JobBoard(JobBoardProvider.LEVER, path[0])) : Optional.empty();
            }
            if (host.endsWith("ashbyhq.com")) {
                if (host.startsWith("api.")) {
                    // /posting-api/job-board/{name}
                    return path.length >= 3 && path[1].equals("job-board")
                            ? Optional.of(new JobBoard(JobBoardProvider.ASHBY, path[2]))
                            : Optional.empty();
                }
                return path.length >= 1 ? Optional.of(new JobBoard(JobBoardProvider.ASHBY, path[0])) : Optional.empty();
            }
        } catch (IllegalArgumentException notABoard) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static String[] segments(String rawPath) {
        if (rawPath == null) {
            return new String[0];
        }
        return java.util.Arrays.stream(rawPath.split("/"))
                .filter(segment -> !segment.isEmpty())
                .map(segment -> URLDecoder.decode(segment, StandardCharsets.UTF_8))
                .toArray(String[]::new);
    }

    private static String param(String query, String name) {
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
