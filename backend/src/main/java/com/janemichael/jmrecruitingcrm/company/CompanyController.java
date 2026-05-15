package com.janemichael.jmrecruitingcrm.company;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyRepository companyRepository;

    public CompanyController(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    @GetMapping
    public List<Company> getAllCompanies() {
        return companyRepository.findAll();
    }

    @PostMapping
    public Company createCompany(@Valid @RequestBody Company company) {
        return companyRepository.save(company);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Company> getCompanyById(@PathVariable Long id) {
        return companyRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Company> updateCompany(
            @PathVariable Long id,
            @Valid @RequestBody Company updatedCompany
    )
    {
        return companyRepository.findById(id)
                .map(company -> {
                    company.setName(updatedCompany.getName());
                    company.setWebsite(updatedCompany.getWebsite());
                    company.setLocation(updatedCompany.getLocation());
                    company.setCompanySize(updatedCompany.getCompanySize());
                    company.setStatus(updatedCompany.getStatus());
                    company.setLinkedinProfile(updatedCompany.getLinkedinProfile());
                    company.setNotes(updatedCompany.getNotes());

                    Company savedCompany = companyRepository.save(company);
                    return ResponseEntity.ok(savedCompany);
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
}