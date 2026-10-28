package com.familyhub.demo.service;

import com.familyhub.demo.model.FamilyPhoto;
import com.familyhub.demo.repository.FamilyPhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * At startup, moves photos uploaded before photos became files out of the database and
 * into their family's folder, one at a time. A photo that cannot be written stays in
 * the database and is served from there; the next startup tries again. Not run by
 * one-off commands such as {@code reset-password}, which start no web server.
 */
@Slf4j
@Component
@ConditionalOnWebApplication
@RequiredArgsConstructor
public class PhotoFileMigrator implements ApplicationRunner {
    private final FamilyPhotoRepository photoRepository;
    private final PhotoFiles photoFiles;
    private final TransactionTemplate transactions;

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    /** Moves every photo still in the database to a file; returns how many moved. */
    public int migrate() {
        List<UUID> ids = photoRepository.findIdsStillInTheDatabase();
        int moved = 0;
        for (UUID id : ids) {
            try {
                Boolean done = transactions.execute(status -> photoRepository.findById(id).map(this::toFile).orElse(false));
                if (Boolean.TRUE.equals(done)) {
                    moved++;
                }
            } catch (RuntimeException e) {
                log.warn("Photo kept in the database for now, photoId={}: {}", id, e.toString());
            }
        }
        if (!ids.isEmpty()) {
            log.info("Photos moved out of the database, moved={}, remaining={}", moved, ids.size() - moved);
        }
        return moved;
    }

    private boolean toFile(FamilyPhoto photo) {
        if (photo.getFileName() != null || photo.getData() == null) {
            return false;
        }
        Path folder = photoFiles.folderFor(photo.getFamily());
        ZoneId zone = ZoneId.of(FamilyTimezoneResolver.resolveStoredTimezoneOrDefault(photo.getFamily().getTimezone()));
        photo.setFileName(photoFiles.write(folder, photo.getData(), photo.getContentType(), photo.getCreatedAt(), zone));
        photo.setData(null);
        photoRepository.save(photo);
        return true;
    }
}
