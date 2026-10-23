package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.ThemePackSummaryResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.ThemePackService;
import com.familyhub.demo.service.ThemePackService.ExportedPack;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/theme-packs")
@RequiredArgsConstructor
public class ThemePackController {
    private final ThemePackService themePackService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ThemePackSummaryResponse>>> list(@AuthenticationPrincipal Family family) {
        return ResponseEntity.ok(new ApiResponse<>(themePackService.list(family), ""));
    }

    /** The body is the pack file itself; it is validated and stored verbatim. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ThemePackSummaryResponse>> importPack(
            @RequestBody String document,
            @AuthenticationPrincipal Family family
    ) {
        ThemePackSummaryResponse summary = themePackService.importPack(document, family);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(summary, "Theme pack imported"));
    }

    /** The document unwrapped, named as a download, so the same file can be re-imported anywhere. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> export(@PathVariable UUID id, @AuthenticationPrincipal Family family) {
        ExportedPack pack = themePackService.document(id, family);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileSlug(pack.name()) + ".familytheme.json\"")
                .body(pack.document());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Family family) {
        themePackService.delete(id, family);
        return ResponseEntity.noContent().build();
    }

    /** "San Diego Padres" → "san-diego-padres"; never empty. */
    static String fileSlug(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "theme" : slug;
    }
}
