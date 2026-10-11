package com.janemichael.jmrecruitingcrm.common;

import com.janemichael.jmrecruitingcrm.company.CompanyEnums.Segment;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnumCsvTest {

    @Test
    void joinsInDeclarationOrderWithoutDuplicates() {
        assertThat(EnumCsv.join(List.of(Segment.COMPLIANCE_AML, Segment.FRAUD_IDENTITY_RISK, Segment.COMPLIANCE_AML)))
                .isEqualTo("FRAUD_IDENTITY_RISK,COMPLIANCE_AML");
        assertThat(EnumCsv.join(Arrays.asList(Segment.DECISIONING, null))).isEqualTo("DECISIONING");
        assertThat(EnumCsv.join(List.<Segment>of())).isNull();
        assertThat(EnumCsv.join(null)).isNull();
    }

    @Test
    void splitsAndDropsValuesThatNoLongerExist() {
        assertThat(EnumCsv.split("FRAUD_IDENTITY_RISK, RETIRED_SEGMENT,COMPLIANCE_AML", Segment.class))
                .containsExactly(Segment.FRAUD_IDENTITY_RISK, Segment.COMPLIANCE_AML);
        assertThat(EnumCsv.split(null, Segment.class)).isEmpty();
        assertThat(EnumCsv.split(" ", Segment.class)).isEmpty();
    }
}
