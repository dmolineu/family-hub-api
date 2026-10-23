package com.familyhub.demo.controller;

import com.familyhub.demo.config.SecurityConfig;
import com.familyhub.demo.dto.BackupDocument;
import com.familyhub.demo.dto.RestoreSummary;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.security.JwtAuthenticationEntryPoint;
import com.familyhub.demo.security.JwtAuthenticationFilter;
import com.familyhub.demo.security.WithMockFamily;
import com.familyhub.demo.service.BackupExportService;
import com.familyhub.demo.service.BackupRestoreService;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BackupController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class})
@ActiveProfiles("test")
class BackupControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    FamilyService familyService;

    @MockitoBean
    BackupExportService backupExportService;

    @MockitoBean
    BackupRestoreService backupRestoreService;

    private static BackupDocument emptyDocument() {
        return new BackupDocument(
                BackupDocument.CURRENT_VERSION,
                Instant.parse("2026-10-23T18:00:00Z"),
                new BackupDocument.FamilyBackup("Test Family", "America/Los_Angeles", null, null, null),
                List.of(),
                List.of(),
                new BackupDocument.ListsBackup(true, List.of(), List.of()),
                List.of(),
                List.of(),
                List.of()
        );
    }

    @Test
    @WithMockFamily
    void export_returnsTheDocumentAsADownload() throws Exception {
        given(backupExportService.export(any(Family.class))).willReturn(emptyDocument());

        mockMvc.perform(get("/api/backup"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("familyhub-backup-")))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.family.name").value("Test Family"));
    }

    @Test
    @WithMockFamily
    void restore_returnsTheSummary() throws Exception {
        given(backupRestoreService.restore(any(BackupDocument.class), any(Family.class)))
                .willReturn(new RestoreSummary(2, 3, 1, 26, 1, 3));

        mockMvc.perform(post("/api/backup/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 1, "exportedAt": "2026-10-23T18:00:00Z",
                                 "family": {"name": "Test Family"},
                                 "members": [], "chores": [],
                                 "lists": {"showCompletedByDefault": true, "categories": [], "lists": []},
                                 "recipes": [], "meals": [], "events": []}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.members").value(2))
                .andExpect(jsonPath("$.data.recipes").value(26))
                .andExpect(jsonPath("$.message").value("Backup restored"));
    }

    @Test
    @WithMockFamily
    void restore_withoutTheEnvelope_isRejected() throws Exception {
        mockMvc.perform(post("/api/backup/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hello\": \"world\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void export_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/backup"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.httpStatus").value(401));
    }
}
