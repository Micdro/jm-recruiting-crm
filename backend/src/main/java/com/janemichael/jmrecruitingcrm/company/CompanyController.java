package com.janemichael.jmrecruitingcrm.company;

import com.janemichael.jmrecruitingcrm.common.Later;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @GetMapping
    public List<CompanyResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) CompanyStatus status
    ) {
        return companyService.list(q, status);
    }

    @GetMapping("/{id}")
    public CompanyResponse get(@PathVariable Long id) {
        return companyService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyResponse create(@Valid @RequestBody CompanyRequest request) {
        return companyService.create(request);
    }

    @PutMapping("/{id}")
    public CompanyResponse update(@PathVariable Long id, @Valid @RequestBody CompanyRequest request) {
        return companyService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        companyService.delete(id);
    }

    /** Moves the follow-up date to the next business day or a week out. */
    @PostMapping("/{id}/snooze")
    public CompanyResponse snooze(@PathVariable Long id, @Valid @RequestBody Later.Request request) {
        return companyService.snooze(id, request.until());
    }

    /** Undoes your last snooze of this company from the last few minutes. */
    @PostMapping("/{id}/undo")
    public CompanyResponse undo(@PathVariable Long id) {
        return companyService.undo(id);
    }
}
