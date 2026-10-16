package com.janemichael.jmrecruitingcrm.prospects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.company.CompanyEnums.HiringNeed;
import com.janemichael.jmrecruitingcrm.company.CompanyEnums.Industry;
import com.janemichael.jmrecruitingcrm.company.CompanyEnums.Segment;
import com.janemichael.jmrecruitingcrm.contact.BuyingRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FoundTest {

    private final ObjectMapper json = new ObjectMapper();

    private Found.Evidence evidence(String stdout) throws Exception {
        Found.Evidence evidence = new Found.Evidence();
        evidence.addResult(json.readTree("""
                {"type": "code_execution_tool_result", "tool_use_id": "srvtoolu_1",
                 "content": {"type": "code_execution_result", "stdout": %s, "stderr": "", "return_code": 0,
                             "encrypted_stdout": "https://www.linkedin.com/in/hidden-person"}}
                """.formatted(json.writeValueAsString(stdout))));
        return evidence;
    }

    private JsonNode input(String companies) throws Exception {
        return json.readTree("{\"companies\": " + companies + "}");
    }

    @Test
    void keepsContactDetailsTheSearchesShowedAndDropsTheRest() throws Exception {
        Found.Evidence evidence = evidence("""
                Northwind Risk raises $30M Series B https://news.example/northwind-series-b
                Dana Ruiz - CTO - Northwind Risk | LinkedIn https://www.linkedin.com/in/dana-ruiz-42/
                Press contact: Dana Ruiz, dana.ruiz@northwind-risk.example, +1 (212) 555-0147
                """);
        Found.Result result = Found.read(input("""
                [{"name": "Northwind Risk", "website": "www.northwind-risk.example/",
                  "location": "New York, US", "what_they_do": "Fraud models for credit unions.",
                  "industry": "banks", "segments": ["FRAUD_IDENTITY_RISK", "not-a-segment", "decisioning"],
                  "hiring_need": "FDE", "signal": "Series B of $30M in September 2026",
                  "sources": ["https://news.example/northwind-series-b", "javascript:alert(1)", "not a url"],
                  "people": [
                    {"name": "Dana Ruiz", "title": "CTO", "role": "BUYER",
                     "linkedin_url": "https://uk.linkedin.com/in/dana-ruiz-42?trk=public",
                     "email": "Dana.Ruiz@northwind-risk.example", "phone": "212.555.0147"},
                    {"name": "Lee Park", "title": "VP Customer Engineering", "role": "influencer",
                     "linkedin_url": "https://www.linkedin.com/in/lee-park", "email": "lee@northwind-risk.example",
                     "phone": "+1 646 555 0199"},
                    {"name": "Sam Hidden", "title": "Head of Talent",
                     "linkedin_url": "https://www.linkedin.com/in/hidden-person"}
                  ]}]
                """), evidence, 10);

        assertThat(result.companies()).hasSize(1);
        Found.Company company = result.companies().getFirst();
        assertThat(company.website()).isEqualTo("https://www.northwind-risk.example");
        assertThat(company.industry()).isEqualTo(Industry.BANKS);
        assertThat(company.segments()).containsExactly(Segment.FRAUD_IDENTITY_RISK, Segment.DECISIONING);
        assertThat(company.hiringNeed()).isEqualTo(HiringNeed.FDE);
        assertThat(company.sources()).containsExactly("https://news.example/northwind-series-b");

        Found.Person dana = company.people().get(0);
        assertThat(dana.linkedinUrl()).isEqualTo("https://www.linkedin.com/in/dana-ruiz-42");
        assertThat(dana.email()).isEqualTo("Dana.Ruiz@northwind-risk.example");
        assertThat(dana.phone()).isEqualTo("212.555.0147");
        assertThat(dana.role()).isEqualTo(BuyingRole.BUYER);

        // Lee's details weren't in anything the searches returned, so they're left out.
        Found.Person lee = company.people().get(1);
        assertThat(lee.linkedinUrl()).isNull();
        assertThat(lee.email()).isNull();
        assertThat(lee.phone()).isNull();
        assertThat(lee.role()).isEqualTo(BuyingRole.INFLUENCER);

        // Encrypted fields aren't evidence: Claude never saw them.
        Found.Person sam = company.people().get(2);
        assertThat(sam.linkedinUrl()).isNull();
        assertThat(sam.role()).isEqualTo(BuyingRole.OTHER);

        assertThat(result.withheld()).isEqualTo(4);
    }

    @Test
    void aProfileMustMatchWholeNotJustTheStartOfAnother() throws Exception {
        Found.Evidence evidence = evidence("https://www.linkedin.com/in/anna-lee-9 and linkedin.com/in/bo%C3%A9");
        assertThat(evidence.showsLinkedin("https://www.linkedin.com/in/anna-lee-9")).isTrue();
        assertThat(evidence.showsLinkedin("https://www.linkedin.com/in/anna-lee")).isFalse();
        assertThat(evidence.showsLinkedin("https://www.linkedin.com/in/anna")).isFalse();
        assertThat(evidence.showsLinkedin("https://www.linkedin.com/in/boé")).isTrue();
    }

    @Test
    void skipsCompaniesWithoutANameOrSignalAndRepeatsAndStopsAtTheLimit() throws Exception {
        Found.Result result = Found.read(input("""
                [{"name": "  ", "signal": "x"},
                 {"name": "No Signal Inc", "signal": ""},
                 {"name": "Ledgerlight AI", "signal": "Hiring FDEs"},
                 {"name": "Ledgerlight, Inc.", "signal": "Same company again"},
                 {"name": "Second Co", "signal": "Raised a seed round"},
                 {"name": "Third Co", "signal": "New bank customer"}]
                """), new Found.Evidence(), 2);
        assertThat(result.companies()).extracting(Found.Company::name).containsExactly("Ledgerlight AI", "Second Co");
        assertThat(result.companies().getFirst().industry()).isEqualTo(Industry.UNCLEAR);
        assertThat(result.companies().getFirst().hiringNeed()).isEqualTo(HiringNeed.NOT_SURE);
    }

    @Test
    void readsNothingFromAnEmptyOrOddAnswer() throws Exception {
        assertThat(Found.read(null, new Found.Evidence(), 10).companies()).isEmpty();
        assertThat(Found.read(json.readTree("{}"), new Found.Evidence(), 10).companies()).isEmpty();
        assertThat(Found.read(json.readTree("{\"companies\": \"none\"}"), new Found.Evidence(), 10).companies()).isEmpty();
    }

    @Test
    void cleansFields() throws Exception {
        assertThat(Found.linkedin("linkedin.com/in/jo-smith/")).isEqualTo("https://www.linkedin.com/in/jo-smith");
        assertThat(Found.linkedin("https://www.linkedin.com/company/acme")).isNull();
        assertThat(Found.linkedin("https://www.linkedin.com/in/")).isNull();

        assertThat(Found.email("mailto:pat@acme.example")).isEqualTo("pat@acme.example");
        assertThat(Found.email("careers@acme.example")).isNull();
        assertThat(Found.email("Info@acme.example")).isNull();
        assertThat(Found.email("not an email")).isNull();

        assertThat(Found.phone("call +1 (415) 555-0100")).isEqualTo("+1 (415) 555-0100");
        assertThat(Found.phone("555-0100")).isNull();

        assertThat(Found.website("acme.example")).isEqualTo("https://acme.example");
        assertThat(Found.website("http://WWW.Acme.example/about")).isEqualTo("https://www.acme.example/about");
        assertThat(Found.website("localhost")).isNull();

        JsonNode node = json.readTree("{\"signal\": \"  Raised   a\\nround  \", \"long\": \"" + "x".repeat(700) + "\"}");
        assertThat(Found.text(node, "signal", 600)).isEqualTo("Raised a round");
        assertThat(Found.text(node, "long", 600)).hasSize(600).endsWith("…");
        assertThat(Found.text(node, "missing", 10)).isNull();
    }

    @Test
    void phonesMatchOnTheirLastTenDigits() throws Exception {
        Found.Evidence evidence = evidence("Office: 212-555-0147. Founded 2019, 12 staff.");
        assertThat(evidence.showsPhone("+1 212 555 0147")).isTrue();
        assertThat(evidence.showsPhone("(212) 555-0148")).isFalse();
        assertThat(evidence.isEmpty()).isFalse();
        assertThat(new Found.Evidence().isEmpty()).isTrue();
    }

    @Test
    void segmentsAndSourcesIgnoreJunk() throws Exception {
        assertThat(Found.segments(json.readTree("[\"compliance aml\", \"COMPLIANCE_AML\", 4]")))
                .containsExactly(Segment.COMPLIANCE_AML);
        assertThat(Found.sources(json.readTree("[\"https://a.example/1\", \"https://a.example/1\", \"ftp://x\"]")))
                .isEqualTo(List.of("https://a.example/1"));
    }
}
