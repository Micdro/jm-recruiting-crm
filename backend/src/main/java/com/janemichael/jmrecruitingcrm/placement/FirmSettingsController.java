package com.janemichael.jmrecruitingcrm.placement;

import com.janemichael.jmrecruitingcrm.placement.FirmSettings.InvoiceSettings;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The firm's details printed on invoices. */
@RestController
@RequestMapping("/api/firm")
public class FirmSettingsController {

    private final FirmSettings settings;

    public FirmSettingsController(FirmSettings settings) {
        this.settings = settings;
    }

    @GetMapping("/invoice-settings")
    public InvoiceSettings get() {
        return settings.invoiceSettings();
    }

    @PutMapping("/invoice-settings")
    public InvoiceSettings save(@Valid @RequestBody InvoiceSettings request) {
        return settings.saveInvoiceSettings(request);
    }
}
