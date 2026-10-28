package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.PhotoFolderListingResponse;
import com.familyhub.demo.dto.PhotoFolderRequest;
import com.familyhub.demo.dto.PhotoResponse;
import com.familyhub.demo.dto.PhotoStorageResponse;
import org.springframework.web.bind.annotation.RequestParam;
import com.familyhub.demo.service.FolderPhotoService;
import com.familyhub.demo.dto.PhotoSourceResponse;
import com.familyhub.demo.dto.PhotoSourceRequest;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyPhoto;
import com.familyhub.demo.service.PhotoFolderBrowser;
import com.familyhub.demo.service.PhotoService;
import com.familyhub.demo.service.PhotoStorageService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PutMapping;
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
    private final PhotoStorageService photoStorageService;
    private final FolderPhotoService folderPhotoService;
    private final PhotoFolderBrowser photoFolderBrowser;

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
                .body(photoService.content(family, id));
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

    /** The folder of existing photos shown in the slideshow, and how its reading went. */
    @GetMapping("/source")
    public ResponseEntity<ApiResponse<PhotoSourceResponse>> source(@AuthenticationPrincipal Family family) {
        return ResponseEntity.ok(new ApiResponse<>(folderPhotoService.status(family), ""));
    }

    /** Chooses (or, empty, clears) the folder of existing photos (a parent's PIN); read in the background. */
    @PutMapping("/source")
    public ResponseEntity<ApiResponse<PhotoSourceResponse>> chooseSource(
            @AuthenticationPrincipal Family family,
            @Valid @RequestBody PhotoSourceRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ApiResponse<>(folderPhotoService.choose(family, request), "Reading the folder"));
    }

    /** A random handful of the folder's pictures for one showing of the slideshow. */
    @GetMapping("/folder")
    public ResponseEntity<ApiResponse<List<String>>> folderSample(
            @AuthenticationPrincipal Family family,
            @RequestParam(defaultValue = "200") int sample
    ) {
        return ResponseEntity.ok(new ApiResponse<>(folderPhotoService.sample(family, sample), ""));
    }

    /** One of the folder's pictures, shrunk for the screen and the right way up. */
    @GetMapping("/folder/{id}/content")
    public ResponseEntity<byte[]> folderContent(@AuthenticationPrincipal Family family, @PathVariable UUID id) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(IMMUTABLE_PRIVATE)
                .body(folderPhotoService.content(family, id));
    }

    /**
     * The folders inside a folder under the photo roots (the roots themselves without
     * one), for picking the photo folder by tapping through them.
     */
    @GetMapping("/folders")
    public ResponseEntity<ApiResponse<PhotoFolderListingResponse>> folders(
            @RequestParam(required = false) String path
    ) {
        return ResponseEntity.ok(new ApiResponse<>(photoFolderBrowser.list(path), ""));
    }

    /** Where the family's photos live and where they may go. */
    @GetMapping("/storage")
    public ResponseEntity<ApiResponse<PhotoStorageResponse>> storage(@AuthenticationPrincipal Family family) {
        return ResponseEntity.ok(new ApiResponse<>(photoStorageService.status(family), ""));
    }

    /**
     * Moves the family's photos to another folder (a parent's PIN). The move runs in the
     * background; the response says it started, and the storage status says how it went.
     */
    @PutMapping("/storage")
    public ResponseEntity<ApiResponse<PhotoStorageResponse>> moveStorage(
            @AuthenticationPrincipal Family family,
            @Valid @RequestBody PhotoFolderRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ApiResponse<>(photoStorageService.move(family, request), "Moving the photos"));
    }

    /** Removes a photo. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Family family, @PathVariable UUID id) {
        photoService.delete(family, id);
        return ResponseEntity.noContent().build();
    }
}
