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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dev profile opts back into the H2 console with {@code security.h2-console.enabled};
 * that must open the console path and relax framing only to same-origin, never disable it.
 */
@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "security.h2-console.enabled=true")
class SecurityHardeningDevConsoleTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    FamilyService familyService;

    @Test
    void h2Console_isOpenWhenEnabled() throws Exception {
        // No console servlet is registered in a slice test, so "open" means the request
        // reaches the dispatcher (404) instead of being stopped by the entry point (401).
        mockMvc.perform(get("/h2-console"))
                .andExpect(status().isNotFound());
    }

    @Test
    void responses_allowSameOriginFramingWhenEnabled() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
    }
}
