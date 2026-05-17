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

    public List<Company> getAllCompanies() {
        return companyRepository.findAll();
    }

    public Optional<Company> getCompanyById(Long id) {
        return companyRepository.findById(id);
    }

    public Company createCompany(Company company) {
        return companyRepository.save(company);
    }

    @Transactional
    public Optional<Company> updateCompany(Long id, Company updatedCompany) {
        return companyRepository.findById(id)
                .map(company -> {
                    company.setName(updatedCompany.getName());
                    company.setWebsite(updatedCompany.getWebsite());
                    company.setLocation(updatedCompany.getLocation());
                    company.setCompanySize(updatedCompany.getCompanySize());
                    company.setStatus(updatedCompany.getStatus());
                    company.setLinkedinProfile(updatedCompany.getLinkedinProfile());
                    company.setNotes(updatedCompany.getNotes());

                    return companyRepository.save(company);
                });
    }

    @Transactional
    public boolean deleteCompany(Long id) {
        int deletedRows = companyRepository.deleteCompanyById(id);
        return deletedRows > 0;
    }
}