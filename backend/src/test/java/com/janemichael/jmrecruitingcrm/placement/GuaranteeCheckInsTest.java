package com.janemichael.jmrecruitingcrm.placement;

import com.janemichael.jmrecruitingcrm.placement.GuaranteeCheckIns.CheckIn;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Guarantee check-ins 30 and 60 days after the start. */
class GuaranteeCheckInsTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    @Test
    void theFirstCheckInIsDueThirtyDaysInAndStaysDueUntilDone() {
        Placement placement = placement(START.plusDays(90));

        assertThat(GuaranteeCheckIns.next(placement, START.plusDays(10)))
                .contains(new CheckIn(30, START.plusDays(30), false));
        assertThat(GuaranteeCheckIns.next(placement, START.plusDays(30)))
                .contains(new CheckIn(30, START.plusDays(30), true));
        assertThat(GuaranteeCheckIns.next(placement, START.plusDays(45)))
                .contains(new CheckIn(30, START.plusDays(30), true));

        placement.setCheckedInOn(START.plusDays(45));
        assertThat(GuaranteeCheckIns.next(placement, START.plusDays(45)))
                .contains(new CheckIn(60, START.plusDays(60), false));
        assertThat(GuaranteeCheckIns.next(placement, START.plusDays(61)))
                .contains(new CheckIn(60, START.plusDays(60), true));

        placement.setCheckedInOn(START.plusDays(61));
        assertThat(GuaranteeCheckIns.next(placement, START.plusDays(70))).isEmpty();
    }

    @Test
    void oneCheckInCoversAMissedOne() {
        Placement placement = placement(START.plusDays(90));

        // The 30-day check-in was missed: by day 65 it's the 60-day one that's due, and only that.
        assertThat(GuaranteeCheckIns.next(placement, START.plusDays(65)))
                .contains(new CheckIn(60, START.plusDays(60), true));

        placement.setCheckedInOn(START.plusDays(65));
        assertThat(GuaranteeCheckIns.next(placement, START.plusDays(66))).isEmpty();
    }

    @Test
    void nothingIsDueOutsideTheGuarantee() {
        assertThat(GuaranteeCheckIns.next(placement(START.plusDays(90)), START.plusDays(91))).isEmpty();
        assertThat(GuaranteeCheckIns.next(placement(START.plusDays(45)), START.plusDays(50)))
                .contains(new CheckIn(30, START.plusDays(30), true));

        Placement shortGuarantee = placement(START.plusDays(45));
        shortGuarantee.setCheckedInOn(START.plusDays(31));
        assertThat(GuaranteeCheckIns.next(shortGuarantee, START.plusDays(40))).isEmpty();

        Placement noStart = placement(null);
        noStart.setStartDate(null);
        assertThat(GuaranteeCheckIns.next(noStart, START)).isEmpty();
        assertThat(GuaranteeCheckIns.next(placement(START.plusDays(90)), null)).isEmpty();
    }

    @Test
    void withoutAnEndDateBothCheckInsStillCount() {
        Placement open = placement(null);
        assertThat(GuaranteeCheckIns.next(open, START.plusDays(200)))
                .contains(new CheckIn(60, START.plusDays(60), true));
    }

    private static Placement placement(LocalDate guaranteeEnd) {
        Placement placement = new Placement();
        placement.setStartDate(START);
        placement.setGuaranteeEndDate(guaranteeEnd);
        return placement;
    }
}
