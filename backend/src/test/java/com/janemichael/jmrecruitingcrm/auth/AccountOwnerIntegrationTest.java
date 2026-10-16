package com.janemichael.jmrecruitingcrm.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.TestSettings;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The account owner: one admin who controls spending, whom other admins can't lock out or take over. */
@SpringBootTest(properties = TestSettings.ENCRYPTION_KEY)
@Transactional
class AccountOwnerIntegrationTest {

    private static final String OWNER = "owner@jane-michael.test";
    private static final String ADMIN = "other-admin@jane-michael.test";
    private static final String MEMBER = "member@jane-michael.test";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mockMvc;
    private Cookie csrf;
    private AppUser owner;
    private AppUser admin;
    private AppUser member;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = account(OWNER, "Owner Person", UserRole.ADMIN, true);
        admin = account(ADMIN, "Other Admin", UserRole.ADMIN, false);
        member = account(MEMBER, "Team Member", UserRole.MEMBER, false);
        csrf = mockMvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    @Test
    void theOwnerIsShownToEveryone() throws Exception {
        mockMvc.perform(send(MEMBER, "MEMBER", get("/api/users"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == '" + OWNER + "')].accountOwner").value(true))
                .andExpect(jsonPath("$[?(@.email == '" + ADMIN + "')].accountOwner").value(false));
        mockMvc.perform(send(OWNER, "ADMIN", get("/api/auth/me"), null))
                .andExpect(jsonPath("$.accountOwner").value(true));
    }

    @Test
    void otherAdminsCantDemoteDeactivateOrTakeOverTheOwner() throws Exception {
        mockMvc.perform(send(ADMIN, "ADMIN", put("/api/users/" + owner.getId()),
                        Map.of("displayName", "Owner Person", "role", "MEMBER", "active", true)))
                .andExpect(status().isForbidden());
        mockMvc.perform(send(ADMIN, "ADMIN", put("/api/users/" + owner.getId()),
                        Map.of("displayName", "Owner Person", "role", "ADMIN", "active", false)))
                .andExpect(status().isForbidden());
        mockMvc.perform(send(ADMIN, "ADMIN", post("/api/users/" + owner.getId() + "/password"),
                        Map.of("password", "a-brand-new-password-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messages[0]").value(containsString("reset with the CRM key")));
        mockMvc.perform(send(ADMIN, "ADMIN", post("/api/users/" + admin.getId() + "/account-owner"), null))
                .andExpect(status().isForbidden());

        // Renaming is fine, and admins still manage everyone else.
        mockMvc.perform(send(ADMIN, "ADMIN", put("/api/users/" + owner.getId()),
                        Map.of("displayName", "Michael", "role", "ADMIN", "active", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountOwner").value(true));
        mockMvc.perform(send(ADMIN, "ADMIN", post("/api/users/" + member.getId() + "/password"),
                        Map.of("password", "a-brand-new-password-1")))
                .andExpect(status().isNoContent());
    }

    @Test
    void theOwnerCanHandOwnershipToATeammate() throws Exception {
        mockMvc.perform(send(OWNER, "ADMIN", post("/api/users/" + owner.getId() + "/account-owner"), null))
                .andExpect(status().isBadRequest());

        mockMvc.perform(send(OWNER, "ADMIN", post("/api/users/" + member.getId() + "/account-owner"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == '" + MEMBER + "')].accountOwner").value(true))
                .andExpect(jsonPath("$[?(@.email == '" + MEMBER + "')].role").value("ADMIN"))
                .andExpect(jsonPath("$[?(@.email == '" + OWNER + "')].accountOwner").value(false));

        assertThat(users.findById(owner.getId()).orElseThrow().isAccountOwner()).isFalse();
        assertThat(users.findById(owner.getId()).orElseThrow().isAdmin()).isTrue();
        assertThat(users.findFirstByAccountOwnerTrueAndActiveTrue()).map(AppUser::getEmail).contains(MEMBER);
    }

    @Test
    void ownershipGoesOnlyToActiveTeammates() throws Exception {
        member.setActive(false);
        users.save(member);
        mockMvc.perform(send(OWNER, "ADMIN", post("/api/users/" + member.getId() + "/account-owner"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value("Reactivate Team Member first"));
    }

    private AppUser account(String email, String name, UserRole role, boolean accountOwner) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setDisplayName(name);
        user.setRole(role);
        user.setActive(true);
        user.setAccountOwner(accountOwner);
        user.setPasswordHash(passwordEncoder.encode("owner-test-password-1"));
        return users.save(user);
    }

    private MockHttpServletRequestBuilder send(String email, String role, MockHttpServletRequestBuilder request,
                                               Object payload) throws Exception {
        MockHttpServletRequestBuilder signedIn = request
                .with(user(email).roles(role))
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue());
        return payload == null
                ? signedIn
                : signedIn.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }
}
