package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.BackupDocument;
import com.familyhub.demo.dto.RestoreSummary;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.BackupExportService;
import com.familyhub.demo.service.BackupRestoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/backup")
@RequiredArgsConstructor
public class BackupController {
    private final BackupExportService backupExportService;
    private final BackupRestoreService backupRestoreService;

    /** The document itself, unwrapped, so the response body is the backup file. */
    @GetMapping
    public ResponseEntity<BackupDocument> export(@AuthenticationPrincipal Family family) {
        BackupDocument document = backupExportService.export(family);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"familyhub-backup-" + LocalDate.now() + ".json\"")
                .body(document);
    }

    @PostMapping("/restore")
    public ResponseEntity<ApiResponse<RestoreSummary>> restore(
            @Valid @RequestBody BackupDocument document,
            @AuthenticationPrincipal Family family
    ) {
        RestoreSummary summary = backupRestoreService.restore(document, family);
        return ResponseEntity.ok(new ApiResponse<>(summary, "Backup restored"));
    }
}
