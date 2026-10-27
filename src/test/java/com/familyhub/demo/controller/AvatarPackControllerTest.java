package com.familyhub.demo.controller;

import com.familyhub.demo.config.SecurityConfig;
import com.familyhub.demo.dto.AvatarPackSummaryResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.security.JwtAuthenticationEntryPoint;
import com.familyhub.demo.security.JwtAuthenticationFilter;
import com.familyhub.demo.security.WithMockFamily;
import com.familyhub.demo.service.AvatarPackService;
import com.familyhub.demo.service.AvatarPackService.ExportedPack;
import com.familyhub.demo.service.FamilyService;
import com.familyhub.demo.service.JwtService;
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

@WebMvcTest(AvatarPackController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class})
@ActiveProfiles("test")
class AvatarPackControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    FamilyService familyService;

    @MockitoBean
    AvatarPackService avatarPackService;

    private static final UUID PACK_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String DOCUMENT = "{\"version\":1,\"name\":\"Star Wars\",\"characters\":[]}";

    private static AvatarPackSummaryResponse summary() {
        return new AvatarPackSummaryResponse(PACK_ID, "Star Wars", 12, List.of("Star Wars"), DOCUMENT.length(),
                Instant.parse("2026-10-27T12:00:00Z"));
    }

    @Test
    @WithMockFamily
    void list_returnsSummariesWithoutTheImages() throws Exception {
        given(avatarPackService.list(any(Family.class))).willReturn(List.of(summary()));

        mockMvc.perform(get("/api/avatar-packs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(PACK_ID.toString()))
                .andExpect(jsonPath("$.data[0].name").value("Star Wars"))
                .andExpect(jsonPath("$.data[0].characterCount").value(12))
                .andExpect(jsonPath("$.data[0].groups[0]").value("Star Wars"))
                .andExpect(jsonPath("$.data[0].sizeBytes").value(DOCUMENT.length()));
    }

    @Test
    @WithMockFamily
    void importPack_returns201WithTheSummary() throws Exception {
        given(avatarPackService.importPack(eq(DOCUMENT), any(Family.class))).willReturn(summary());

        mockMvc.perform(post("/api/avatar-packs").contentType(MediaType.APPLICATION_JSON).content(DOCUMENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(PACK_ID.toString()))
                .andExpect(jsonPath("$.message").value("Avatar pack imported"));
    }

    @Test
    @WithMockFamily
    void importPack_invalidDocument_returns400WithTheReason() throws Exception {
        given(avatarPackService.importPack(any(), any(Family.class)))
                .willThrow(new BadRequestException("Character yoda needs a PNG, JPEG or WebP image of 64 KB or less"));

        mockMvc.perform(post("/api/avatar-packs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Character yoda needs a PNG, JPEG or WebP image of 64 KB or less"));
    }

    @Test
    @WithMockFamily
    void document_returnsTheRawPackAsADownload() throws Exception {
        given(avatarPackService.document(eq(PACK_ID), any(Family.class))).willReturn(new ExportedPack("Star Wars", DOCUMENT));

        mockMvc.perform(get("/api/avatar-packs/{id}", PACK_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"star-wars.familyavatars.json\""))
                .andExpect(content().json(DOCUMENT));
    }

    @Test
    @WithMockFamily
    void document_unknownPack_returns404() throws Exception {
        given(avatarPackService.document(eq(PACK_ID), any(Family.class)))
                .willThrow(new ResourceNotFoundException("AvatarPack", PACK_ID));

        mockMvc.perform(get("/api/avatar-packs/{id}", PACK_ID)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockFamily
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/avatar-packs/{id}", PACK_ID)).andExpect(status().isNoContent());
        verify(avatarPackService).delete(eq(PACK_ID), any(Family.class));
    }

    @Test
    @WithMockFamily
    void delete_unknownPack_returns404() throws Exception {
        willThrow(new ResourceNotFoundException("AvatarPack", PACK_ID))
                .given(avatarPackService).delete(eq(PACK_ID), any(Family.class));
        mockMvc.perform(delete("/api/avatar-packs/{id}", PACK_ID)).andExpect(status().isNotFound());
    }

    @Test
    void anonymous_isRejected() throws Exception {
        mockMvc.perform(get("/api/avatar-packs")).andExpect(status().isUnauthorized());
    }
}
