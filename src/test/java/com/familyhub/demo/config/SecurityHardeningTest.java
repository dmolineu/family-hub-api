package com.familyhub.demo.config;

import com.familyhub.demo.controller.HealthController;
import com.familyhub.demo.security.JwtAuthenticationEntryPoint;
import com.familyhub.demo.security.JwtAuthenticationFilter;
import com.familyhub.demo.service.FamilyService;
import com.familyhub.demo.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production hardening of the security filter chain. The H2 console is a dev-only
 * convenience and must not be reachable without authentication by default, and
 * responses must forbid framing (the frame-options header was disabled globally
 * just to make that console's frames work).
 */
@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class})
@ActiveProfiles("test")
class SecurityHardeningTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    FamilyService familyService;

    @Test
    void h2Console_requiresAuthenticationByDefault() throws Exception {
        mockMvc.perform(get("/h2-console"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void responses_denyFramingByDefault() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }
}
