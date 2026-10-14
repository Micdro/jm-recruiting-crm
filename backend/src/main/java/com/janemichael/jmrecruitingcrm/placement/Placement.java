package com.janemichael.jmrecruitingcrm.placement;

import com.janemichael.jmrecruitingcrm.candidate.Candidate;
import com.janemichael.jmrecruitingcrm.common.Auditable;
import com.janemichael.jmrecruitingcrm.common.EncryptedDecimal;
import com.janemichael.jmrecruitingcrm.common.EncryptedInteger;
import com.janemichael.jmrecruitingcrm.common.EncryptedString;
import com.janemichael.jmrecruitingcrm.search.Search;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A hire the firm made: start date, fee and invoice status. */
@Entity
public class Placement extends Auditable {

    public enum InvoiceStatus {
        NOT_INVOICED,
        INVOICED,
        PAID
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "search_id", nullable = false)
    private Search search;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false)
    private Candidate candidate;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Convert(converter = EncryptedInteger.class)
    @Column(name = "base_salary")
    private Integer baseSalary;

    @Column(name = "fee_percent")
    private BigDecimal feePercent;

    @Column(name = "fee_amount")
    @Convert(converter = EncryptedDecimal.class)
    private BigDecimal feeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_status", nullable = false)
    private InvoiceStatus invoiceStatus = InvoiceStatus.NOT_INVOICED;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "paid_date")
    private LocalDate paidDate;

    @Column(name = "guarantee_end_date")
    private LocalDate guaranteeEndDate;

    @Convert(converter = EncryptedString.class)
    private String notes;

    @Column(name = "invoice_number")
    private String invoiceNumber;

    /** When the invoice is due. Older invoices without one are due 30 days after they were sent. */
    @Column(name = "invoice_due_date")
    private LocalDate invoiceDueDate;

    @Column(name = "bill_to_name")
    @Convert(converter = EncryptedString.class)
    private String billToName;

    @Column(name = "bill_to_email")
    @Convert(converter = EncryptedString.class)
    private String billToEmail;

    @Column(name = "bill_to_address")
    @Convert(converter = EncryptedString.class)
    private String billToAddress;

    @Column(name = "invoice_description")
    @Convert(converter = EncryptedString.class)
    private String invoiceDescription;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Search getSearch() {
        return search;
    }

    public void setSearch(Search search) {
        this.search = search;
    }

    public Candidate getCandidate() {
        return candidate;
    }

    public void setCandidate(Candidate candidate) {
        this.candidate = candidate;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public Integer getBaseSalary() {
        return baseSalary;
    }

    public void setBaseSalary(Integer baseSalary) {
        this.baseSalary = baseSalary;
    }

    public BigDecimal getFeePercent() {
        return feePercent;
    }

    public void setFeePercent(BigDecimal feePercent) {
        this.feePercent = feePercent;
    }

    public BigDecimal getFeeAmount() {
        return feeAmount;
    }

    public void setFeeAmount(BigDecimal feeAmount) {
        this.feeAmount = feeAmount;
    }

    public InvoiceStatus getInvoiceStatus() {
        return invoiceStatus;
    }

    public void setInvoiceStatus(InvoiceStatus invoiceStatus) {
        this.invoiceStatus = invoiceStatus;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public void setInvoiceDate(LocalDate invoiceDate) {
        this.invoiceDate = invoiceDate;
    }

    public LocalDate getPaidDate() {
        return paidDate;
    }

    public void setPaidDate(LocalDate paidDate) {
        this.paidDate = paidDate;
    }

    public LocalDate getGuaranteeEndDate() {
        return guaranteeEndDate;
    }

    public void setGuaranteeEndDate(LocalDate guaranteeEndDate) {
        this.guaranteeEndDate = guaranteeEndDate;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    /** The due date, or for an invoice sent without one, 30 days after it was sent. */
    public LocalDate effectiveDueDate() {
        if (invoiceDueDate != null) {
            return invoiceDueDate;
        }
        return invoiceDate == null ? null : invoiceDate.plusDays(30);
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    public LocalDate getInvoiceDueDate() {
        return invoiceDueDate;
    }

    public void setInvoiceDueDate(LocalDate invoiceDueDate) {
        this.invoiceDueDate = invoiceDueDate;
    }

    public String getBillToName() {
        return billToName;
    }

    public void setBillToName(String billToName) {
        this.billToName = billToName;
    }

    public String getBillToEmail() {
        return billToEmail;
    }

    public void setBillToEmail(String billToEmail) {
        this.billToEmail = billToEmail;
    }

    public String getBillToAddress() {
        return billToAddress;
    }

    public void setBillToAddress(String billToAddress) {
        this.billToAddress = billToAddress;
    }

    public String getInvoiceDescription() {
        return invoiceDescription;
    }

    public void setInvoiceDescription(String invoiceDescription) {
        this.invoiceDescription = invoiceDescription;
    }
}
