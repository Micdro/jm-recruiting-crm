package com.janemichael.jmrecruitingcrm.common;

import com.janemichael.jmrecruitingcrm.contact.OutreachStatus;
import com.janemichael.jmrecruitingcrm.placement.PlacementService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class HelpersTest {

    @Test
    void businessDaysSkipWeekends() {
        LocalDate friday = LocalDate.of(2026, 10, 16);
        assertThat(BusinessDays.add(friday, 1)).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(BusinessDays.add(friday, 3)).isEqualTo(LocalDate.of(2026, 10, 21));
    }

    @Test
    void linkedinUrlsMatchHoweverTheyWerePasted() {
        assertThat(Text.linkedinKey("https://www.LinkedIn.com/in/jane-doe/?trk=abc"))
                .isEqualTo("linkedin.com/in/jane-doe");
        assertThat(Text.linkedinKey("linkedin.com/in/jane-doe")).isEqualTo("linkedin.com/in/jane-doe");
        assertThat(Text.linkedinKey("  ")).isNull();
    }

    @Test
    void labelsAreReadable() {
        assertThat(Labels.of(OutreachStatus.IN_SEQUENCE)).isEqualTo("In sequence");
        assertThat(Labels.of(null)).isEqualTo("None");
    }

    @Test
    void feeIsBaseTimesPercentRoundedToCents() {
        assertThat(PlacementService.calculateFee(215_000, new BigDecimal("22.5")))
                .isEqualByComparingTo("48375.00");
        assertThat(PlacementService.calculateFee(null, new BigDecimal("25"))).isNull();
        assertThat(PlacementService.calculateFee(200_000, null)).isNull();
    }
}
