package com.janemichael.jmrecruitingcrm.company;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanyControllerTest {

    private final CompanyService companyService = mock(CompanyService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new CompanyController(companyService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void getAllCompaniesReturnsCompaniesAsJson() throws Exception {
        CompanyResponse response = new CompanyResponse(
                1L,
                "Jane Michael LLC",
                "https://jane-michael.com",
                "New York, NY",
                "1-10",
                "Prospect",
                "https://linkedin.com/company/jane-michael",
                "AI recruiting firm"
        );

        when(companyService.getAllCompanies()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Jane Michael LLC"))
                .andExpect(jsonPath("$[0].website").value("https://jane-michael.com"))
                .andExpect(jsonPath("$[0].location").value("New York, NY"))
                .andExpect(jsonPath("$[0].companySize").value("1-10"))
                .andExpect(jsonPath("$[0].status").value("Prospect"))
                .andExpect(jsonPath("$[0].linkedinProfile").value("https://linkedin.com/company/jane-michael"))
                .andExpect(jsonPath("$[0].notes").value("AI recruiting firm"));

        verify(companyService).getAllCompanies();
    }

    @Test
    void createCompanyReturnsCreatedCompanyAsJson() throws Exception {
        CompanyRequest request = new CompanyRequest();
        request.setName("Jane Michael LLC");
        request.setWebsite("https://jane-michael.com");
        request.setLocation("New York, NY");
        request.setCompanySize("1-10");
        request.setStatus("Prospect");
        request.setLinkedinProfile("https://linkedin.com/company/jane-michael");
        request.setNotes("AI recruiting firm");

        CompanyResponse response = new CompanyResponse(
                1L,
                "Jane Michael LLC",
                "https://jane-michael.com",
                "New York, NY",
                "1-10",
                "Prospect",
                "https://linkedin.com/company/jane-michael",
                "AI recruiting firm"
        );

        when(companyService.createCompany(any(CompanyRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Jane Michael LLC"))
                .andExpect(jsonPath("$.website").value("https://jane-michael.com"))
                .andExpect(jsonPath("$.location").value("New York, NY"))
                .andExpect(jsonPath("$.companySize").value("1-10"))
                .andExpect(jsonPath("$.status").value("Prospect"))
                .andExpect(jsonPath("$.linkedinProfile").value("https://linkedin.com/company/jane-michael"))
                .andExpect(jsonPath("$.notes").value("AI recruiting firm"));

        verify(companyService).createCompany(any(CompanyRequest.class));
    }

    @Test
    void createCompanyReturnsBadRequestWhenNameIsBlank() throws Exception {
        CompanyRequest request = new CompanyRequest();
        request.setName("");

        mockMvc.perform(post("/api/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.messages[0]").value("Company name is required"));

        verifyNoInteractions(companyService);
    }

    @Test
    void getCompanyByIdReturnsNotFoundWhenCompanyDoesNotExist() throws Exception {
        when(companyService.getCompanyById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/companies/99"))
                .andExpect(status().isNotFound());

        verify(companyService).getCompanyById(99L);
    }

    @Test
    void deleteCompanyReturnsNoContentWhenCompanyWasDeleted() throws Exception {
        when(companyService.deleteCompany(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/companies/1"))
                .andExpect(status().isNoContent());

        verify(companyService).deleteCompany(1L);
    }

    @Test
    void deleteCompanyReturnsNotFoundWhenCompanyDoesNotExist() throws Exception {
        when(companyService.deleteCompany(99L)).thenReturn(false);

        mockMvc.perform(delete("/api/companies/99"))
                .andExpect(status().isNotFound());

        verify(companyService).deleteCompany(99L);
    }
}