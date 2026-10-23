package com.familyhub.demo.controller;

import com.familyhub.demo.config.SecurityConfig;
import com.familyhub.demo.dto.ThemePackPreview;
import com.familyhub.demo.dto.ThemePackSummaryResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.security.JwtAuthenticationEntryPoint;
import com.familyhub.demo.security.JwtAuthenticationFilter;
import com.familyhub.demo.security.WithMockFamily;
import com.familyhub.demo.service.FamilyService;
import com.familyhub.demo.service.JwtService;
import com.familyhub.demo.service.ThemePackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ThemePackController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class})
@ActiveProfiles("test")
class ThemePackControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    FamilyService familyService;

    @MockitoBean
    ThemePackService themePackService;

    private static final UUID PACK_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String DOCUMENT = "{\"version\":1,\"name\":\"Padres\",\"dark\":true,\"tokens\":{\"--primary\":\"#ffc425\"},\"images\":{}}";

    private static ThemePackSummaryResponse summary() {
        return new ThemePackSummaryResponse(PACK_ID, "Padres", true, DOCUMENT.length(), Instant.parse("2026-10-23T12:00:00Z"),
                new ThemePackPreview("#2f241d", null, "#ffc425", null, null, null));
    }

    @Test
    @WithMockFamily
    void list_returnsSummaries() throws Exception {
        given(themePackService.list(any(Family.class))).willReturn(List.of(summary()));

        mockMvc.perform(get("/api/theme-packs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(PACK_ID.toString()))
                .andExpect(jsonPath("$.data[0].name").value("Padres"))
                .andExpect(jsonPath("$.data[0].dark").value(true))
                .andExpect(jsonPath("$.data[0].sizeBytes").value(DOCUMENT.length()))
                .andExpect(jsonPath("$.data[0].preview.primary").value("#ffc425"))
                .andExpect(jsonPath("$.data[0].preview.card").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @WithMockFamily
    void importPack_returns201WithTheSummary() throws Exception {
        given(themePackService.importPack(eq(DOCUMENT), any(Family.class))).willReturn(summary());

        mockMvc.perform(post("/api/theme-packs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DOCUMENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(PACK_ID.toString()))
                .andExpect(jsonPath("$.data.name").value("Padres"));
    }

    @Test
    @WithMockFamily
    void importPack_invalidDocument_returns400WithTheReason() throws Exception {
        given(themePackService.importPack(any(), any(Family.class)))
                .willThrow(new BadRequestException("Theme pack token --evil has an unsafe value"));

        mockMvc.perform(post("/api/theme-packs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Theme pack token --evil has an unsafe value"));
    }

    @Test
    @WithMockFamily
    void export_returnsTheRawDocumentAsADownload() throws Exception {
        given(themePackService.document(eq(PACK_ID), any(Family.class))).willReturn(new com.familyhub.demo.service.ThemePackService.ExportedPack("Padres", DOCUMENT));

        mockMvc.perform(get("/api/theme-packs/{id}", PACK_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"padres.familytheme.json\""))
                .andExpect(content().json(DOCUMENT));
    }

    @Test
    @WithMockFamily
    void export_unknownPack_returns404() throws Exception {
        given(themePackService.document(eq(PACK_ID), any(Family.class)))
                .willThrow(new ResourceNotFoundException("ThemePack", PACK_ID));

        mockMvc.perform(get("/api/theme-packs/{id}", PACK_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockFamily
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/theme-packs/{id}", PACK_ID))
                .andExpect(status().isNoContent());

        verify(themePackService).delete(eq(PACK_ID), any(Family.class));
    }

    @Test
    @WithMockFamily
    void delete_unknownPack_returns404() throws Exception {
        willThrow(new ResourceNotFoundException("ThemePack", PACK_ID))
                .given(themePackService).delete(eq(PACK_ID), any(Family.class));

        mockMvc.perform(delete("/api/theme-packs/{id}", PACK_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymous_isRejected() throws Exception {
        mockMvc.perform(get("/api/theme-packs"))
                .andExpect(status().isUnauthorized());
    }
}
