package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.PhotoResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyPhoto;
import com.familyhub.demo.service.PhotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
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

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Screensaver photos. The upload body is the image itself (image/jpeg or image/png),
 * not a multipart form, so no multipart limits need tuning. Images are served with
 * a long private cache lifetime: a photo's bytes never change under the same id.
 */
@RestController
@RequestMapping("/api/photos")
@RequiredArgsConstructor
public class PhotoController {
    private static final CacheControl IMMUTABLE_PRIVATE =
            CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();

    private final PhotoService photoService;

    /** The family's photos, newest first. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<PhotoResponse>>> list(@AuthenticationPrincipal Family family) {
        return ResponseEntity.ok(new ApiResponse<>(photoService.list(family), ""));
    }

    /** Adds a photo; the body is the JPEG or PNG file. */
    @PostMapping(consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    public ResponseEntity<ApiResponse<PhotoResponse>> upload(
            @AuthenticationPrincipal Family family,
            @RequestBody(required = false) byte[] body
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(photoService.upload(family, body), "Photo added"));
    }

    /** The photo itself. */
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal Family family, @PathVariable UUID id) {
        FamilyPhoto photo = photoService.get(family, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.getContentType()))
                .cacheControl(IMMUTABLE_PRIVATE)
                .body(photo.getData());
    }

    /** A 480 px JPEG thumbnail for the Photos grid. */
    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@AuthenticationPrincipal Family family, @PathVariable UUID id) {
        FamilyPhoto photo = photoService.get(family, id);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(IMMUTABLE_PRIVATE)
                .body(photo.getThumbnail());
    }

    /** Removes a photo. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Family family, @PathVariable UUID id) {
        photoService.delete(family, id);
        return ResponseEntity.noContent().build();
    }
}
