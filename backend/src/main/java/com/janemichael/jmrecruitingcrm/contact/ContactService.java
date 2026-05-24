package com.janemichael.jmrecruitingcrm.contact;

import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ContactService {

    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;

    public ContactService(ContactRepository contactRepository, CompanyRepository companyRepository) {
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
    }

    public List<ContactResponse> getAllContacts() {
        return contactRepository.findAll()
                .stream()
                .map(ContactResponse::fromEntity)
                .toList();
    }

    public Optional<ContactResponse> getContactById(Long id) {
        return contactRepository.findById(id)
                .map(ContactResponse::fromEntity);
    }

    @Transactional
    public Optional<ContactResponse> createContact(ContactRequest request) {
        return companyRepository.findById(request.getCompanyId())
                .map(company -> {
                    Contact contact = new Contact();
                    applyRequestToContact(request, contact, company);

                    Contact savedContact = contactRepository.save(contact);

                    return ContactResponse.fromEntity(savedContact);
                });
    }

    @Transactional
    public Optional<ContactResponse> updateContact(Long id, ContactRequest request) {
        Optional<Company> companyOptional = companyRepository.findById(request.getCompanyId());

        if (companyOptional.isEmpty()) {
            return Optional.empty();
        }

        return contactRepository.findById(id)
                .map(contact -> {
                    applyRequestToContact(request, contact, companyOptional.get());

                    Contact savedContact = contactRepository.save(contact);

                    return ContactResponse.fromEntity(savedContact);
                });
    }

    @Transactional
    public boolean deleteContact(Long id) {
        int deletedRows = contactRepository.deleteContactById(id);
        return deletedRows > 0;
    }

    private void applyRequestToContact(ContactRequest request, Contact contact, Company company) {
        contact.setName(request.getName());
        contact.setTitle(request.getTitle());
        contact.setEmail(request.getEmail());
        contact.setLinkedinUrl(request.getLinkedinUrl());
        contact.setRelationshipStatus(request.getRelationshipStatus());
        contact.setLastContactedDate(request.getLastContactedDate());
        contact.setNextFollowUpDate(request.getNextFollowUpDate());
        contact.setNotes(request.getNotes());
        contact.setCompany(company);
    }
}