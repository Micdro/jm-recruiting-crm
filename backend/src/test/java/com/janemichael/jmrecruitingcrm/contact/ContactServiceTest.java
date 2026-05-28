package com.janemichael.jmrecruitingcrm.contact;

import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ContactServiceTest {

    private final ContactRepository contactRepository = mock(ContactRepository.class);
    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final ContactService contactService = new ContactService(contactRepository, companyRepository);

    @Test
    void getAllContactsReturnsContactResponses() {
        Company company = createCompany();

        Contact contact = new Contact();
        contact.setId(1L);
        contact.setName("Jane Doe");
        contact.setTitle("VP Engineering");
        contact.setEmail("jane@example.com");
        contact.setCompany(company);

        when(contactRepository.findAll()).thenReturn(List.of(contact));

        List<ContactResponse> result = contactService.getAllContacts();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getName()).isEqualTo("Jane Doe");
        assertThat(result.get(0).getTitle()).isEqualTo("VP Engineering");
        assertThat(result.get(0).getEmail()).isEqualTo("jane@example.com");
        assertThat(result.get(0).getCompanyId()).isEqualTo(10L);
        assertThat(result.get(0).getCompanyName()).isEqualTo("Jane Michael LLC");

        verify(contactRepository).findAll();
    }

    @Test
    void getContactByIdReturnsContactResponseWhenContactExists() {
        Company company = createCompany();

        Contact contact = new Contact();
        contact.setId(1L);
        contact.setName("Jane Doe");
        contact.setCompany(company);

        when(contactRepository.findById(1L)).thenReturn(Optional.of(contact));

        Optional<ContactResponse> result = contactService.getContactById(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
        assertThat(result.get().getName()).isEqualTo("Jane Doe");
        assertThat(result.get().getCompanyId()).isEqualTo(10L);

        verify(contactRepository).findById(1L);
    }

    @Test
    void getContactByIdReturnsEmptyWhenContactDoesNotExist() {
        when(contactRepository.findById(99L)).thenReturn(Optional.empty());

        Optional<ContactResponse> result = contactService.getContactById(99L);

        assertThat(result).isEmpty();

        verify(contactRepository).findById(99L);
    }

    @Test
    void createContactSavesContactWhenCompanyExists() {
        Company company = createCompany();
        ContactRequest request = createRequest();

        Contact savedContact = createSavedContact(company);

        when(companyRepository.findById(10L)).thenReturn(Optional.of(company));
        when(contactRepository.save(any(Contact.class))).thenReturn(savedContact);

        Optional<ContactResponse> result = contactService.createContact(request);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
        assertThat(result.get().getName()).isEqualTo("Jane Doe");
        assertThat(result.get().getCompanyId()).isEqualTo(10L);
        assertThat(result.get().getCompanyName()).isEqualTo("Jane Michael LLC");

        ArgumentCaptor<Contact> contactCaptor = ArgumentCaptor.forClass(Contact.class);
        verify(contactRepository).save(contactCaptor.capture());

        Contact contactPassedToRepository = contactCaptor.getValue();

        assertThat(contactPassedToRepository.getName()).isEqualTo("Jane Doe");
        assertThat(contactPassedToRepository.getTitle()).isEqualTo("VP Engineering");
        assertThat(contactPassedToRepository.getEmail()).isEqualTo("jane@example.com");
        assertThat(contactPassedToRepository.getLinkedinUrl()).isEqualTo("https://linkedin.com/in/janedoe");
        assertThat(contactPassedToRepository.getRelationshipStatus()).isEqualTo("Warm");
        assertThat(contactPassedToRepository.getLastContactedDate()).isEqualTo(LocalDate.of(2026, 5, 23));
        assertThat(contactPassedToRepository.getNextFollowUpDate()).isEqualTo(LocalDate.of(2026, 5, 30));
        assertThat(contactPassedToRepository.getNotes()).isEqualTo("Interested in AI platform engineers");
        assertThat(contactPassedToRepository.getCompany()).isEqualTo(company);

        verify(companyRepository).findById(10L);
    }

    @Test
    void createContactReturnsEmptyWhenCompanyDoesNotExist() {
        ContactRequest request = createRequest();

        when(companyRepository.findById(10L)).thenReturn(Optional.empty());

        Optional<ContactResponse> result = contactService.createContact(request);

        assertThat(result).isEmpty();

        verify(companyRepository).findById(10L);
        verify(contactRepository, never()).save(any(Contact.class));
    }

    @Test
    void updateContactUpdatesExistingContactWhenCompanyExists() {
        Company company = createCompany();
        ContactRequest request = createRequest();

        Contact existingContact = new Contact();
        existingContact.setId(1L);
        existingContact.setName("Old Name");
        existingContact.setCompany(company);

        when(companyRepository.findById(10L)).thenReturn(Optional.of(company));
        when(contactRepository.findById(1L)).thenReturn(Optional.of(existingContact));
        when(contactRepository.save(existingContact)).thenReturn(existingContact);

        Optional<ContactResponse> result = contactService.updateContact(1L, request);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
        assertThat(result.get().getName()).isEqualTo("Jane Doe");
        assertThat(result.get().getTitle()).isEqualTo("VP Engineering");
        assertThat(result.get().getCompanyId()).isEqualTo(10L);

        verify(companyRepository).findById(10L);
        verify(contactRepository).findById(1L);
        verify(contactRepository).save(existingContact);
    }

    @Test
    void updateContactReturnsEmptyWhenCompanyDoesNotExist() {
        ContactRequest request = createRequest();

        when(companyRepository.findById(10L)).thenReturn(Optional.empty());

        Optional<ContactResponse> result = contactService.updateContact(1L, request);

        assertThat(result).isEmpty();

        verify(companyRepository).findById(10L);
        verify(contactRepository, never()).findById(anyLong());
        verify(contactRepository, never()).save(any(Contact.class));
    }

    @Test
    void updateContactReturnsEmptyWhenContactDoesNotExist() {
        Company company = createCompany();
        ContactRequest request = createRequest();

        when(companyRepository.findById(10L)).thenReturn(Optional.of(company));
        when(contactRepository.findById(99L)).thenReturn(Optional.empty());

        Optional<ContactResponse> result = contactService.updateContact(99L, request);

        assertThat(result).isEmpty();

        verify(companyRepository).findById(10L);
        verify(contactRepository).findById(99L);
        verify(contactRepository, never()).save(any(Contact.class));
    }

    @Test
    void deleteContactReturnsTrueWhenRowWasDeleted() {
        when(contactRepository.deleteContactById(1L)).thenReturn(1);

        boolean result = contactService.deleteContact(1L);

        assertThat(result).isTrue();

        verify(contactRepository).deleteContactById(1L);
    }

    @Test
    void deleteContactReturnsFalseWhenNoRowWasDeleted() {
        when(contactRepository.deleteContactById(99L)).thenReturn(0);

        boolean result = contactService.deleteContact(99L);

        assertThat(result).isFalse();

        verify(contactRepository).deleteContactById(99L);
    }

    private Company createCompany() {
        Company company = new Company();
        company.setId(10L);
        company.setName("Jane Michael LLC");
        return company;
    }

    private ContactRequest createRequest() {
        ContactRequest request = new ContactRequest();
        request.setName("Jane Doe");
        request.setTitle("VP Engineering");
        request.setEmail("jane@example.com");
        request.setLinkedinUrl("https://linkedin.com/in/janedoe");
        request.setRelationshipStatus("Warm");
        request.setLastContactedDate(LocalDate.of(2026, 5, 23));
        request.setNextFollowUpDate(LocalDate.of(2026, 5, 30));
        request.setNotes("Interested in AI platform engineers");
        request.setCompanyId(10L);
        return request;
    }

    private Contact createSavedContact(Company company) {
        Contact contact = new Contact();
        contact.setId(1L);
        contact.setName("Jane Doe");
        contact.setTitle("VP Engineering");
        contact.setEmail("jane@example.com");
        contact.setLinkedinUrl("https://linkedin.com/in/janedoe");
        contact.setRelationshipStatus("Warm");
        contact.setLastContactedDate(LocalDate.of(2026, 5, 23));
        contact.setNextFollowUpDate(LocalDate.of(2026, 5, 30));
        contact.setNotes("Interested in AI platform engineers");
        contact.setCompany(company);
        return contact;
    }
}