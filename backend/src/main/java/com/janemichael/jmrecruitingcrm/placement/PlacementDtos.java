package com.janemichael.jmrecruitingcrm.placement;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
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

    /** What goes on the invoice for a placement. The amount is the placement's fee. */
    public record InvoiceRequest(
            @NotBlank(message = "Enter the invoice number")
            @Size(max = 40, message = "The invoice number must be 40 characters or fewer")
            String invoiceNumber,

            @NotNull(message = "Enter the invoice date")
            LocalDate invoiceDate,

            @NotNull(message = "Enter the due date")
            LocalDate dueDate,

            @NotBlank(message = "Enter who the invoice is for")
            @Size(max = 200, message = "The name must be 200 characters or fewer")
            String billToName,

            @Email(message = "Enter a valid email address")
            @Size(max = 255, message = "The email must be 255 characters or fewer")
            String billToEmail,

            @Size(max = 500, message = "The address must be 500 characters or fewer")
            String billToAddress,

            @NotBlank(message = "Describe what the invoice is for")
            @Size(max = 500, message = "The description must be 500 characters or fewer")
            String description
    ) {
    }

    public record NextInvoiceNumber(String invoiceNumber) {
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
            String notes,
            String invoiceNumber,
            /** When it's due: the date set on the invoice, or 30 days after it was sent. */
            LocalDate invoiceDueDate,
            String billToName,
            String billToEmail,
            String billToAddress,
            String invoiceDescription
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
                    placement.getNotes(),
                    placement.getInvoiceNumber(),
                    placement.effectiveDueDate(),
                    placement.getBillToName(),
                    placement.getBillToEmail(),
                    placement.getBillToAddress(),
                    placement.getInvoiceDescription()
            );
        }
    }
}
