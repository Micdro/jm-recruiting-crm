package com.janemichael.jmrecruitingcrm.prospects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProspectResearchTest {

    private static final String KEY = "sk-ant-test-key-0001";

    /** Answers with the queued replies, in order, and keeps what was sent. */
    private static final class ScriptedApi extends ClaudeApi {
        final Deque<Object> replies = new ArrayDeque<>();
        final List<String> sent = new ArrayList<>();

        ScriptedApi reply(int status, String body) {
            replies.add(new Reply(status, body, null));
            return this;
        }

        ScriptedApi fail(IOException problem) {
            replies.add(problem);
            return this;
        }

        @Override
        public Reply messages(String apiKey, String body) throws IOException {
            assertThat(apiKey).isEqualTo(KEY);
            sent.add(body);
            Object next = replies.removeFirst();
            if (next instanceof IOException problem) {
                throw problem;
            }
            return (Reply) next;
        }
    }

    private final ObjectMapper json = new ObjectMapper();

    private static ProspectResearch.Request request(int count) {
        return new ProspectResearch.Request(KEY, count, "Prefer US companies.", List.of("Ledgerlight AI", "Acme"),
                LocalDate.of(2026, 10, 16));
    }

    private static ProspectResearch research(ScriptedApi api) {
        ProspectResearch research = new ProspectResearch(api);
        research.sleeper = millis -> { };
        return research;
    }

    private static final String RECORDED = """
            {"type": "tool_use", "id": "toolu_1", "name": "record_prospects",
             "input": {"companies": [{"name": "Northwind Risk", "signal": "Hiring forward deployed engineers",
                                      "sources": ["https://jobs.example/northwind"],
                                      "people": [{"name": "Dana Ruiz", "title": "CTO",
                                                  "linkedin_url": "https://www.linkedin.com/in/dana-ruiz-42"}]}]}}
            """;

    @Test
    void continuesAPausedTurnAndAddsUpWhatItUsed() throws Exception {
        ScriptedApi api = new ScriptedApi()
                .reply(200, """
                        {"stop_reason": "pause_turn", "container": {"id": "container_1"},
                         "content": [{"type": "server_tool_use", "id": "srvtoolu_1", "name": "code_execution",
                                      "input": {"code": "search()"}},
                                     {"type": "code_execution_tool_result", "tool_use_id": "srvtoolu_1",
                                      "content": {"type": "code_execution_result",
                                                  "stdout": "Dana Ruiz CTO https://www.linkedin.com/in/dana-ruiz-42"}}],
                         "usage": {"input_tokens": 100000, "output_tokens": 2000,
                                   "server_tool_use": {"web_search_requests": 6}}}
                        """)
                .reply(200, """
                        {"stop_reason": "tool_use", "content": [%s],
                         "usage": {"input_tokens": 50000, "output_tokens": 3000, "cache_read_input_tokens": 10000,
                                   "server_tool_use": {"web_search_requests": 4}}}
                        """.formatted(RECORDED));

        ProspectResearch.Outcome outcome = research(api).run(request(10));

        assertThat(outcome.found().companies()).hasSize(1);
        Found.Person dana = outcome.found().companies().getFirst().people().getFirst();
        assertThat(dana.linkedinUrl()).isEqualTo("https://www.linkedin.com/in/dana-ruiz-42");
        assertThat(outcome.usage().searches()).isEqualTo(10);
        assertThat(outcome.usage().inputTokens()).isEqualTo(150_000);
        // 150k input ($0.30) + 5k output ($0.05) + 10k cache reads ($0.001) + 10 searches ($0.10)
        assertThat(outcome.usage().costCents()).isEqualTo(46);

        assertThat(api.sent).hasSize(2);
        JsonNode first = json.readTree(api.sent.get(0));
        assertThat(first.path("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(first.path("tools").get(0).path("type").asText()).isEqualTo("web_search_20260318");
        assertThat(first.path("tools").get(0).path("max_uses").asInt()).isEqualTo(38);
        assertThat(first.path("tools").get(1).path("name").asText()).isEqualTo("record_prospects");
        assertThat(first.path("system").asText()).contains("Prefer US companies.").contains("October 16, 2026");
        assertThat(first.path("messages").get(0).path("content").asText())
                .contains("Find up to 10 new prospects").contains("Ledgerlight AI; Acme");
        assertThat(first.has("container")).isFalse();

        JsonNode second = json.readTree(api.sent.get(1));
        assertThat(second.path("container").asText()).isEqualTo("container_1");
        assertThat(second.path("messages")).hasSize(2);
        assertThat(second.path("messages").get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(second.path("messages").get(1).path("content").get(1).path("type").asText())
                .isEqualTo("code_execution_tool_result");
    }

    @Test
    void asksOnceForTheListWhenClaudeStopsWithoutIt() throws Exception {
        ScriptedApi api = new ScriptedApi()
                .reply(200, "{\"stop_reason\": \"end_turn\", \"content\": [{\"type\": \"text\", \"text\": \"Here they are.\"}]}")
                .reply(200, "{\"stop_reason\": \"tool_use\", \"content\": [" + RECORDED + "]}");

        ProspectResearch.Outcome outcome = research(api).run(request(5));

        assertThat(outcome.found().companies()).hasSize(1);
        // Not in any search result, so the profile is left out.
        assertThat(outcome.found().companies().getFirst().people().getFirst().linkedinUrl()).isNull();
        assertThat(outcome.found().withheld()).isEqualTo(1);
        JsonNode second = json.readTree(api.sent.get(1));
        assertThat(second.path("messages")).hasSize(3);
        assertThat(second.path("messages").get(2).path("content").asText()).contains("record_prospects");
    }

    @Test
    void givesUpIfClaudeStillDoesntListThem() {
        String text = "{\"stop_reason\": \"end_turn\", \"content\": [{\"type\": \"text\", \"text\": \"Done.\"}], "
                + "\"usage\": {\"input_tokens\": 1000, \"output_tokens\": 10}}";
        ScriptedApi api = new ScriptedApi().reply(200, text).reply(200, text);

        assertThatThrownBy(() -> research(api).run(request(5)))
                .isInstanceOfSatisfying(ProspectResearch.ResearchException.class, problem -> {
                    assertThat(problem.getMessage()).contains("without listing");
                    assertThat(problem.usage.inputTokens()).isEqualTo(2000);
                });
    }

    @Test
    void retriesWhenTheApiIsBusyOrUnreachable() throws Exception {
        ScriptedApi api = new ScriptedApi()
                .reply(529, "{\"type\": \"error\", \"error\": {\"type\": \"overloaded_error\", \"message\": \"Overloaded\"}}")
                .fail(new IOException("connection reset"))
                .reply(200, "{\"stop_reason\": \"tool_use\", \"content\": [" + RECORDED + "]}");

        assertThat(research(api).run(request(5)).found().companies()).hasSize(1);
        assertThat(api.sent).hasSize(3);
    }

    @Test
    void explainsWhyTheApiTurnedItDown() {
        ScriptedApi badKey = new ScriptedApi()
                .reply(401, "{\"type\": \"error\", \"error\": {\"type\": \"authentication_error\", \"message\": \"invalid x-api-key\"}}");
        assertThatThrownBy(() -> research(badKey).run(request(5)))
                .hasMessageContaining("didn't accept the API key");

        ScriptedApi noCredit = new ScriptedApi()
                .reply(400, "{\"type\": \"error\", \"error\": {\"type\": \"invalid_request_error\", "
                        + "\"message\": \"Your credit balance is too low to access the Claude API.\"}}");
        assertThatThrownBy(() -> research(noCredit).run(request(5)))
                .hasMessage("The Claude API turned the request down: Your credit balance is too low to access the Claude API.");

        ScriptedApi down = new ScriptedApi();
        for (int attempt = 0; attempt <= ProspectResearch.MAX_RETRIES; attempt++) {
            down.reply(503, "Service Unavailable");
        }
        assertThatThrownBy(() -> research(down).run(request(5))).hasMessageContaining("had a problem (503)");
    }

    @Test
    void waitsAsLongAsTheApiAsks() {
        assertThat(ProspectResearch.backoff(0, null)).isEqualTo(5_000);
        assertThat(ProspectResearch.backoff(2, null)).isEqualTo(20_000);
        assertThat(ProspectResearch.backoff(0, 30L)).isEqualTo(30_000);
        assertThat(ProspectResearch.backoff(0, 900L)).isEqualTo(120_000);
        assertThat(ProspectResearch.maxSearches(1)).isEqualTo(11);
        assertThat(ProspectResearch.maxSearches(25)).isEqualTo(40);
    }
}
