package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.AvatarPackSummaryResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.AvatarPackService;
import com.familyhub.demo.service.AvatarPackService.ExportedPack;
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
import java.util.UUID;

/** Import, list, fetch and delete the family's avatar packs (sets of characters to pick from). */
@RestController
@RequestMapping("/api/avatar-packs")
@RequiredArgsConstructor
public class AvatarPackController {
    private final AvatarPackService avatarPackService;

    /** The family's packs without their pictures. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<AvatarPackSummaryResponse>>> list(@AuthenticationPrincipal Family family) {
        return ResponseEntity.ok(new ApiResponse<>(avatarPackService.list(family), ""));
    }

    /** The body is the pack file itself; it is validated and stored verbatim. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<AvatarPackSummaryResponse>> importPack(
            @RequestBody String document,
            @AuthenticationPrincipal Family family
    ) {
        AvatarPackSummaryResponse summary = avatarPackService.importPack(document, family);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(summary, "Avatar pack imported"));
    }

    /**
     * The document unwrapped and named as a download: the avatar picker reads the
     * characters from it, and the same file can be re-imported anywhere.
     */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> document(@PathVariable UUID id, @AuthenticationPrincipal Family family) {
        ExportedPack pack = avatarPackService.document(id, family);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + ThemePackController.fileSlug(pack.name()) + ".familyavatars.json\"")
                .body(pack.document());
    }

    /** Members who chose one of its characters keep their copy. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Family family) {
        avatarPackService.delete(id, family);
        return ResponseEntity.noContent().build();
    }
}
