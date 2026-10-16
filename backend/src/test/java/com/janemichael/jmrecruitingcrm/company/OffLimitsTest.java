package com.janemichael.jmrecruitingcrm.company;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OffLimitsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    private static Company company(CompanyStatus status, LocalDate offLimitsUntil) {
        Company company = new Company();
        company.setName("Ledgerlight AI");
        company.setStatus(status);
        company.setOffLimitsUntil(offLimitsUntil);
        return company;
    }

    @Test
    void aProspectIsNotOffLimits() {
        assertThat(OffLimits.of(company(CompanyStatus.ENGAGED, null), 0, null, TODAY)).isNull();
    }

    @Test
    void aSignedClientOrAnOpenSearchMakesTheirPeopleOffLimits() {
        assertThat(OffLimits.of(company(CompanyStatus.CONTRACT_SIGNED, null), 0, null, TODAY))
                .isEqualTo(new OffLimits(true, null));
        assertThat(OffLimits.of(company(CompanyStatus.LONG_TERM_INTEREST, null), 1, null, TODAY))
                .isEqualTo(new OffLimits(true, null));
    }

    @Test
    void theYearAfterTheLastPlacementStartsStaysOffLimits() {
        Company past = company(CompanyStatus.COMPLETE, null);

        assertThat(OffLimits.of(past, 0, LocalDate.of(2026, 3, 2), TODAY))
                .isEqualTo(new OffLimits(false, LocalDate.of(2027, 3, 2)));
        // A year on, they can be approached again.
        assertThat(OffLimits.of(past, 0, LocalDate.of(2025, 10, 15), TODAY)).isNull();
    }

    @Test
    void aDateSetByHandReplacesTheYear() {
        LocalDate placed = LocalDate.of(2026, 3, 2);

        assertThat(OffLimits.of(company(CompanyStatus.COMPLETE, LocalDate.of(2028, 1, 1)), 0, placed, TODAY))
                .isEqualTo(new OffLimits(false, LocalDate.of(2028, 1, 1)));
        // Today ends it now.
        assertThat(OffLimits.of(company(CompanyStatus.COMPLETE, TODAY), 0, placed, TODAY)).isNull();
        // It doesn't end the protection while the firm still works for them.
        assertThat(OffLimits.of(company(CompanyStatus.PLACING, TODAY), 0, placed, TODAY))
                .isEqualTo(new OffLimits(true, null));
    }
}
