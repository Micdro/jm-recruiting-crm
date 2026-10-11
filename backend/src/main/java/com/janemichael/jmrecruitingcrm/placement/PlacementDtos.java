package com.janemichael.jmrecruitingcrm.placement;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class PlacementDtos {

    private PlacementDtos() {
    }

    public record PlacementRequest(
            @NotNull(message = "Search is required")
            Long searchId,

            @NotNull(message = "Candidate is required")
            Long candidateId,

            LocalDate startDate,

            @PositiveOrZero(message = "Base salary can't be negative")
            Integer baseSalary,

            @DecimalMin(value = "0", message = "Fee must be between 0 and 100 percent")
            @DecimalMax(value = "100", message = "Fee must be between 0 and 100 percent")
            BigDecimal feePercent,

            @DecimalMin(value = "0", message = "Fee amount can't be negative")
            BigDecimal feeAmount,

            Placement.InvoiceStatus invoiceStatus,

            LocalDate invoiceDate,

            LocalDate paidDate,

            LocalDate guaranteeEndDate,

            @Size(max = 2000, message = "Notes must be 2000 characters or fewer")
            String notes
    ) {
    }

    public record PlacementResponse(
            Long id,
            Long searchId,
            String searchTitle,
            Long companyId,
            String companyName,
            Long candidateId,
            String candidateName,
            LocalDate startDate,
            Integer baseSalary,
            BigDecimal feePercent,
            BigDecimal feeAmount,
            Placement.InvoiceStatus invoiceStatus,
            LocalDate invoiceDate,
            LocalDate paidDate,
            LocalDate guaranteeEndDate,
            String notes
    ) {

        public static PlacementResponse from(Placement placement) {
            return new PlacementResponse(
                    placement.getId(),
                    placement.getSearch().getId(),
                    placement.getSearch().getTitle(),
                    placement.getSearch().getCompany().getId(),
                    placement.getSearch().getCompany().getName(),
                    placement.getCandidate().getId(),
                    placement.getCandidate().getName(),
                    placement.getStartDate(),
                    placement.getBaseSalary(),
                    placement.getFeePercent(),
                    placement.getFeeAmount(),
                    placement.getInvoiceStatus(),
                    placement.getInvoiceDate(),
                    placement.getPaidDate(),
                    placement.getGuaranteeEndDate(),
                    placement.getNotes()
            );
        }
    }
}
