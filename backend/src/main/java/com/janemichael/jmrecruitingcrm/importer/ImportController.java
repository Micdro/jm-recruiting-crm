package com.janemichael.jmrecruitingcrm.importer;

import com.janemichael.jmrecruitingcrm.importer.ImportDtos.CandidateImportRequest;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.CompanyImportRequest;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.ContactImportRequest;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.ImportResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/companies")
    public ImportResult importCompanies(@Valid @RequestBody CompanyImportRequest request) {
        return importService.importCompanies(request);
    }

    @PostMapping("/contacts")
    public ImportResult importContacts(@Valid @RequestBody ContactImportRequest request) {
        return importService.importContacts(request);
    }

    @PostMapping("/candidates")
    public ImportResult importCandidates(@Valid @RequestBody CandidateImportRequest request) {
        return importService.importCandidates(request);
    }
}
