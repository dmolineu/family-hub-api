package com.familyhub.demo.service;

import com.familyhub.demo.dto.PhotoStorageResponse.MoveResult;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyPhoto;
import com.familyhub.demo.repository.FamilyPhotoRepository;
import com.familyhub.demo.repository.FamilyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moves a family's photo library to another folder, safely: every photo is copied
 * first; only when all arrived does the family switch folders, and only then are the
 * old copies removed. A failure part-way removes the new copies and leaves everything
 * as it was. Photos still in the database are written straight to the new folder.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoMover {
    private final FamilyRepository familyRepository;
    private final FamilyPhotoRepository photoRepository;
    private final PhotoFiles photoFiles;
    private final PhotoMoveTracker tracker;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /** Runs the move in the background; the caller has already marked it started. */
    @Async
    public void move(UUID familyId, Path to) {
        int moved = 0;
        String error = null;
        try {
            moved = moveNow(familyId, to);
        } catch (RuntimeException e) {
            error = "The photos could not be moved: " + e.getMessage();
            log.warn("Photo move failed, familyId={}, to={}", familyId, to, e);
        } finally {
            tracker.finish(familyId, new MoveResult(to.toString(), moved, error, Instant.now(clock)));
        }
    }

    private int moveNow(UUID familyId, Path to) {
        Family family = familyRepository.findById(familyId).orElseThrow();
        Path from = photoFiles.folderFor(family);
        ZoneId zone = ZoneId.of(FamilyTimezoneResolver.resolveStoredTimezoneOrDefault(family.getTimezone()));
        List<FamilyPhoto> photos = transactions.execute(status -> photoRepository.findByFamily(family));
        List<String> copied = new ArrayList<>();
        Map<UUID, String> written = new HashMap<>();
        try {
            for (FamilyPhoto photo : photos) {
                if (photo.getFileName() != null) {
                    photoFiles.copy(from, to, photo.getFileName());
                    copied.add(photo.getFileName());
                } else if (photo.getData() != null) {
                    String name = photoFiles.write(to, photo.getData(), photo.getContentType(), photo.getCreatedAt(), zone);
                    written.put(photo.getId(), name);
                    copied.add(name);
                }
            }
        } catch (RuntimeException e) {
            copied.forEach(name -> photoFiles.deleteQuietly(to, name));
            throw e;
        }

        transactions.executeWithoutResult(status -> {
            Family managed = familyRepository.findById(familyId).orElseThrow();
            managed.setPhotoFolder(to.toString());
            familyRepository.save(managed);
            written.forEach((id, name) -> photoRepository.findById(id).ifPresent(photo -> {
                photo.setFileName(name);
                photo.setData(null);
                photoRepository.save(photo);
            }));
        });

        if (!from.equals(to)) {
            photos.stream()
                    .filter(photo -> photo.getFileName() != null)
                    .forEach(photo -> photoFiles.deleteQuietly(from, photo.getFileName()));
        }
        log.info("Photos moved, familyId={}, from={}, to={}, photos={}", familyId, from, to, copied.size());
        return copied.size();
    }
}
