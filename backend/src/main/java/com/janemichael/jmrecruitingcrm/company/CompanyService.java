package com.janemichael.jmrecruitingcrm.company;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CompanyService {

    private final CompanyRepository companyRepository;

    public CompanyService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    public List<CompanyResponse> getAllCompanies() {
        return companyRepository.findAll()
                .stream()
                .map(CompanyResponse::fromEntity)
                .toList();
    }

    public Optional<CompanyResponse> getCompanyById(Long id) {
        return companyRepository.findById(id)
                .map(CompanyResponse::fromEntity);
    }

    public CompanyResponse createCompany(CompanyRequest request) {
        Company company = new Company();

        company.setName(request.getName());
        company.setWebsite(request.getWebsite());
        company.setLocation(request.getLocation());
        company.setCompanySize(request.getCompanySize());
        company.setStatus(request.getStatus());
        company.setLinkedinProfile(request.getLinkedinProfile());
        company.setNotes(request.getNotes());

        Company savedCompany = companyRepository.save(company);

        return CompanyResponse.fromEntity(savedCompany);
    }

    @Transactional
    public Optional<CompanyResponse> updateCompany(Long id, CompanyRequest request) {
        return companyRepository.findById(id)
                .map(company -> {
                    company.setName(request.getName());
                    company.setWebsite(request.getWebsite());
                    company.setLocation(request.getLocation());
                    company.setCompanySize(request.getCompanySize());
                    company.setStatus(request.getStatus());
                    company.setLinkedinProfile(request.getLinkedinProfile());
                    company.setNotes(request.getNotes());

                    Company savedCompany = companyRepository.save(company);

                    return CompanyResponse.fromEntity(savedCompany);
                });
    }

    @Transactional
    public boolean deleteCompany(Long id) {
        int deletedRows = companyRepository.deleteCompanyById(id);
        return deletedRows > 0;
    }
}