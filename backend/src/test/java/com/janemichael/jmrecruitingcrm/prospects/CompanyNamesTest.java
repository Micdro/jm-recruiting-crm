package com.janemichael.jmrecruitingcrm.prospects;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyNamesTest {

    private static boolean same(String first, String second) {
        Set<String> keys = CompanyNames.keys(first);
        return CompanyNames.keys(second).stream().anyMatch(keys::contains);
    }

    @Test
    void namesMatchLoosely() {
        assertThat(same("Ledgerlight AI, Inc.", "Ledgerlight")).isTrue();
        assertThat(same("Open AI", "OpenAI")).isTrue();
        assertThat(same("The Acme Company", "Acme")).isTrue();
        assertThat(same("Zürich Risk Labs", "zurich risk")).isTrue();
        assertThat(same("Northwind Risk (acquired by Contoso)", "Northwind Risk")).isTrue();
        assertThat(same("Smith & Co Analytics", "Smith and Co Analytics")).isTrue();
        assertThat(same("Northwind Risk", "Northwind Bank")).isFalse();
        assertThat(CompanyNames.keys(null)).isEmpty();
        assertThat(CompanyNames.keys("  ,  ")).isEmpty();
    }

    @Test
    void websitesCompareByHost() {
        assertThat(CompanyNames.domain("https://www.Acme.ai/about")).isEqualTo("acme.ai");
        assertThat(CompanyNames.domain("acme.ai")).isEqualTo("acme.ai");
        assertThat(CompanyNames.domain("https://www.linkedin.com/company/acme")).isNull();
        assertThat(CompanyNames.domain("https://acme.notion.site")).isNull();
        assertThat(CompanyNames.domain("not a site")).isNull();
        assertThat(CompanyNames.domain(null)).isNull();
    }
}
