package com.janemichael.jmrecruitingcrm.contact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.janemichael.jmrecruitingcrm.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContactControllerTest {

    private final ContactService contactService = mock(ContactService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ContactController(contactService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void getAllContactsReturnsContactsAsJson() throws Exception {
        ContactResponse response = createResponse();

        when(contactService.getAllContacts()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/contacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Jane Doe"))
                .andExpect(jsonPath("$[0].title").value("VP Engineering"))
                .andExpect(jsonPath("$[0].email").value("jane@example.com"))
                .andExpect(jsonPath("$[0].linkedinUrl").value("https://linkedin.com/in/janedoe"))
                .andExpect(jsonPath("$[0].relationshipStatus").value("Warm"))
                .andExpect(jsonPath("$[0].lastContactedDate").value("2026-05-23"))
                .andExpect(jsonPath("$[0].nextFollowUpDate").value("2026-05-30"))
                .andExpect(jsonPath("$[0].notes").value("Interested in AI platform engineers"))
                .andExpect(jsonPath("$[0].companyId").value(10))
                .andExpect(jsonPath("$[0].companyName").value("Jane Michael LLC"));

        verify(contactService).getAllContacts();
    }

    @Test
    void createContactReturnsContactAsJsonWhenCompanyExists() throws Exception {
        ContactRequest request = createRequest();
        ContactResponse response = createResponse();

        when(contactService.createContact(any(ContactRequest.class))).thenReturn(Optional.of(response));

        mockMvc.perform(post("/api/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Jane Doe"))
                .andExpect(jsonPath("$.title").value("VP Engineering"))
                .andExpect(jsonPath("$.email").value("jane@example.com"))
                .andExpect(jsonPath("$.companyId").value(10))
                .andExpect(jsonPath("$.companyName").value("Jane Michael LLC"));

        verify(contactService).createContact(any(ContactRequest.class));
    }

    @Test
    void createContactReturnsNotFoundWhenCompanyDoesNotExist() throws Exception {
        ContactRequest request = createRequest();

        when(contactService.createContact(any(ContactRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());

        verify(contactService).createContact(any(ContactRequest.class));
    }

    @Test
    void createContactReturnsBadRequestWhenNameIsBlank() throws Exception {
        ContactRequest request = createRequest();
        request.setName("");

        mockMvc.perform(post("/api/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.messages[0]").value("Contact name is required"));

        verifyNoInteractions(contactService);
    }

    @Test
    void createContactReturnsBadRequestWhenCompanyIdIsMissing() throws Exception {
        ContactRequest request = createRequest();
        request.setCompanyId(null);

        mockMvc.perform(post("/api/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.messages[0]").value("Company ID is required"));

        verifyNoInteractions(contactService);
    }

    @Test
    void createContactReturnsBadRequestWhenEmailIsInvalid() throws Exception {
        ContactRequest request = createRequest();
        request.setEmail("not-an-email");

        mockMvc.perform(post("/api/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.messages[0]").value("Email must be valid"));

        verifyNoInteractions(contactService);
    }

    @Test
    void getContactByIdReturnsContactWhenContactExists() throws Exception {
        ContactResponse response = createResponse();

        when(contactService.getContactById(1L)).thenReturn(Optional.of(response));

        mockMvc.perform(get("/api/contacts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Jane Doe"))
                .andExpect(jsonPath("$.companyId").value(10));

        verify(contactService).getContactById(1L);
    }

    @Test
    void getContactByIdReturnsNotFoundWhenContactDoesNotExist() throws Exception {
        when(contactService.getContactById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/contacts/99"))
                .andExpect(status().isNotFound());

        verify(contactService).getContactById(99L);
    }

    @Test
    void deleteContactReturnsNoContentWhenContactWasDeleted() throws Exception {
        when(contactService.deleteContact(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/contacts/1"))
                .andExpect(status().isNoContent());

        verify(contactService).deleteContact(1L);
    }

    @Test
    void deleteContactReturnsNotFoundWhenContactDoesNotExist() throws Exception {
        when(contactService.deleteContact(99L)).thenReturn(false);

        mockMvc.perform(delete("/api/contacts/99"))
                .andExpect(status().isNotFound());

        verify(contactService).deleteContact(99L);
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

    private ContactResponse createResponse() {
        return new ContactResponse(
                1L,
                "Jane Doe",
                "VP Engineering",
                "jane@example.com",
                "https://linkedin.com/in/janedoe",
                "Warm",
                LocalDate.of(2026, 5, 23),
                LocalDate.of(2026, 5, 30),
                "Interested in AI platform engineers",
                10L,
                "Jane Michael LLC"
        );
    }
}