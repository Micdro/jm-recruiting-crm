package com.janemichael.jmrecruitingcrm.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Reads companies' public job boards. These are the same open APIs the companies' own careers pages
 * use; they need no account or key.
 */
@Component
public class JobBoardClient {

    /** The board doesn't exist (any more) under that name. */
    public static class BoardNotFoundException extends Exception {
        public BoardNotFoundException(String message) {
            super(message);
        }
    }

    private static final String USER_AGENT = "JM-Recruiting-CRM (hiring signals)";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper json = new ObjectMapper();

    /** The board's open roles. */
    public JobPostings.Board fetch(JobBoard board) throws IOException, InterruptedException, BoardNotFoundException {
        String name = board.encoded();
        return switch (board.provider()) {
            case GREENHOUSE -> JobPostings.parse(board.provider(),
                    get("https://boards-api.greenhouse.io/v1/boards/" + name + "/jobs"));
            case LEVER -> JobPostings.parse(board.provider(), lever(name));
            case ASHBY -> JobPostings.parse(board.provider(),
                    get("https://api.ashbyhq.com/posting-api/job-board/" + name));
            case NONE -> throw new IllegalArgumentException("No board to read");
        };
    }

    /** The company name on a Greenhouse board, or null. Used to check a board found by guessing its name. */
    public String greenhouseName(JobBoard board) throws IOException, InterruptedException, BoardNotFoundException {
        JsonNode root = json.readTree(get("https://boards-api.greenhouse.io/v1/boards/" + board.encoded()));
        JsonNode name = root == null ? null : root.get("name");
        return name == null || name.isNull() ? null : name.asText().trim();
    }

    /** Lever keeps European companies on a separate server. */
    private String lever(String name) throws IOException, InterruptedException, BoardNotFoundException {
        try {
            return get("https://api.lever.co/v0/postings/" + name + "?mode=json");
        } catch (BoardNotFoundException notInTheUs) {
            return get("https://api.eu.lever.co/v0/postings/" + name + "?mode=json");
        }
    }

    private String get(String url) throws IOException, InterruptedException, BoardNotFoundException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response.body();
        }
        if (status == 429) {
            throw new IOException("The job board asked the CRM to slow down");
        }
        if (status >= 400 && status < 500) {
            throw new BoardNotFoundException("No job board under that name");
        }
        throw new IOException("The job board answered with an error (" + status + ")");
    }
}
