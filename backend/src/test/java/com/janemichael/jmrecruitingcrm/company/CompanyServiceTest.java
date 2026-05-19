package com.janemichael.jmrecruitingcrm.company;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CompanyServiceTest {

    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final CompanyService companyService = new CompanyService(companyRepository);

    @Test
    void getAllCompaniesReturnsCompanyResponses() {
        Company company = new Company();
        company.setId(1L);
        company.setName("Jane Michael LLC");
        company.setWebsite("https://jane-michael.com");

        when(companyRepository.findAll()).thenReturn(List.of(company));

        List<CompanyResponse> result = companyService.getAllCompanies();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getName()).isEqualTo("Jane Michael LLC");
        assertThat(result.get(0).getWebsite()).isEqualTo("https://jane-michael.com");

        verify(companyRepository).findAll();
    }

    @Test
    void getCompanyByIdReturnsCompanyResponseWhenCompanyExists() {
        Company company = new Company();
        company.setId(1L);
        company.setName("Jane Michael LLC");

        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

        Optional<CompanyResponse> result = companyService.getCompanyById(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
        assertThat(result.get().getName()).isEqualTo("Jane Michael LLC");

        verify(companyRepository).findById(1L);
    }

    @Test
    void getCompanyByIdReturnsEmptyWhenCompanyDoesNotExist() {
        when(companyRepository.findById(99L)).thenReturn(Optional.empty());

        Optional<CompanyResponse> result = companyService.getCompanyById(99L);

        assertThat(result).isEmpty();

        verify(companyRepository).findById(99L);
    }

    @Test
    void createCompanySavesCompanyAndReturnsCompanyResponse() {
        CompanyRequest request = new CompanyRequest();
        request.setName("Jane Michael LLC");
        request.setWebsite("https://jane-michael.com");
        request.setLocation("New York, NY");
        request.setCompanySize("1-10");
        request.setStatus("Prospect");
        request.setLinkedinProfile("https://linkedin.com/company/jane-michael");
        request.setNotes("AI recruiting firm");

        Company savedCompany = new Company();
        savedCompany.setId(1L);
        savedCompany.setName(request.getName());
        savedCompany.setWebsite(request.getWebsite());
        savedCompany.setLocation(request.getLocation());
        savedCompany.setCompanySize(request.getCompanySize());
        savedCompany.setStatus(request.getStatus());
        savedCompany.setLinkedinProfile(request.getLinkedinProfile());
        savedCompany.setNotes(request.getNotes());

        when(companyRepository.save(any(Company.class))).thenReturn(savedCompany);

        CompanyResponse result = companyService.createCompany(request);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Jane Michael LLC");
        assertThat(result.getWebsite()).isEqualTo("https://jane-michael.com");
        assertThat(result.getLocation()).isEqualTo("New York, NY");
        assertThat(result.getCompanySize()).isEqualTo("1-10");
        assertThat(result.getStatus()).isEqualTo("Prospect");
        assertThat(result.getLinkedinProfile()).isEqualTo("https://linkedin.com/company/jane-michael");
        assertThat(result.getNotes()).isEqualTo("AI recruiting firm");

        ArgumentCaptor<Company> companyCaptor = ArgumentCaptor.forClass(Company.class);
        verify(companyRepository).save(companyCaptor.capture());

        Company companyPassedToRepository = companyCaptor.getValue();

        assertThat(companyPassedToRepository.getName()).isEqualTo("Jane Michael LLC");
        assertThat(companyPassedToRepository.getWebsite()).isEqualTo("https://jane-michael.com");
        assertThat(companyPassedToRepository.getLocation()).isEqualTo("New York, NY");
        assertThat(companyPassedToRepository.getCompanySize()).isEqualTo("1-10");
        assertThat(companyPassedToRepository.getStatus()).isEqualTo("Prospect");
        assertThat(companyPassedToRepository.getLinkedinProfile()).isEqualTo("https://linkedin.com/company/jane-michael");
        assertThat(companyPassedToRepository.getNotes()).isEqualTo("AI recruiting firm");
    }

    @Test
    void updateCompanyUpdatesExistingCompanyAndReturnsCompanyResponse() {
        Company existingCompany = new Company();
        existingCompany.setId(1L);
        existingCompany.setName("Old Name");

        CompanyRequest request = new CompanyRequest();
        request.setName("Updated Name");
        request.setWebsite("https://updated.com");
        request.setLocation("Boston, MA");
        request.setCompanySize("11-50");
        request.setStatus("Client");
        request.setLinkedinProfile("https://linkedin.com/company/updated");
        request.setNotes("Updated notes");

        when(companyRepository.findById(1L)).thenReturn(Optional.of(existingCompany));
        when(companyRepository.save(existingCompany)).thenReturn(existingCompany);

        Optional<CompanyResponse> result = companyService.updateCompany(1L, request);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
        assertThat(result.get().getName()).isEqualTo("Updated Name");
        assertThat(result.get().getWebsite()).isEqualTo("https://updated.com");
        assertThat(result.get().getLocation()).isEqualTo("Boston, MA");
        assertThat(result.get().getCompanySize()).isEqualTo("11-50");
        assertThat(result.get().getStatus()).isEqualTo("Client");
        assertThat(result.get().getLinkedinProfile()).isEqualTo("https://linkedin.com/company/updated");
        assertThat(result.get().getNotes()).isEqualTo("Updated notes");

        verify(companyRepository).findById(1L);
        verify(companyRepository).save(existingCompany);
    }

    @Test
    void updateCompanyReturnsEmptyWhenCompanyDoesNotExist() {
        CompanyRequest request = new CompanyRequest();
        request.setName("Updated Name");

        when(companyRepository.findById(99L)).thenReturn(Optional.empty());

        Optional<CompanyResponse> result = companyService.updateCompany(99L, request);

        assertThat(result).isEmpty();

        verify(companyRepository).findById(99L);
        verify(companyRepository, never()).save(any(Company.class));
    }

    @Test
    void deleteCompanyReturnsTrueWhenRowWasDeleted() {
        when(companyRepository.deleteCompanyById(1L)).thenReturn(1);

        boolean result = companyService.deleteCompany(1L);

        assertThat(result).isTrue();

        verify(companyRepository).deleteCompanyById(1L);
    }

    @Test
    void deleteCompanyReturnsFalseWhenNoRowWasDeleted() {
        when(companyRepository.deleteCompanyById(99L)).thenReturn(0);

        boolean result = companyService.deleteCompany(99L);

        assertThat(result).isFalse();

        verify(companyRepository).deleteCompanyById(99L);
    }
}