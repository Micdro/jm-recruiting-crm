package com.janemichael.jmrecruitingcrm.search;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class IntakeReaderTest {

    @Test
    void readsSalariesHoweverTheyreWritten() {
        assertThat(IntakeReader.salary("180000")).isEqualTo(180_000);
        assertThat(IntakeReader.salary("$180,000")).isEqualTo(180_000);
        assertThat(IntakeReader.salary("230k")).isEqualTo(230_000);
        assertThat(IntakeReader.salary("187.5k USD")).isEqualTo(187_500);
        assertThat(IntakeReader.salary("")).isNull();
        assertThat(IntakeReader.salary("competitive")).isNull();
        // Hourly rates and typos aren't base salaries.
        assertThat(IntakeReader.salary("95")).isNull();
    }

    @Test
    void readsTheFeeAsAPercent() {
        assertThat(IntakeReader.percent("20")).isEqualByComparingTo(BigDecimal.valueOf(20));
        assertThat(IntakeReader.percent("22.5%")).isEqualByComparingTo(new BigDecimal("22.5"));
        assertThat(IntakeReader.percent("0")).isNull();
        assertThat(IntakeReader.percent("120")).isNull();
        assertThat(IntakeReader.percent("a fifth")).isNull();
    }

    @Test
    void matchesPeopleIgnoringCaseAccentsAndPunctuation() {
        assertThat(IntakeReader.personKey("José  O'Neil")).isEqualTo(IntakeReader.personKey("jose oneil"));
    }
}
