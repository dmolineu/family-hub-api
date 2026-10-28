package com.familyhub.demo.scheduler;

import com.familyhub.demo.model.Family;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.service.FolderPhotoIndexer;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-reads every family's folder of existing photos once a day, picking up new ones. */
@Component
@RequiredArgsConstructor
public class PhotoFolderScheduler {
    private final FamilyRepository familyRepository;
    private final FolderPhotoIndexer indexer;

    @Scheduled(cron = "${photos.source-index-cron:0 15 4 * * *}")
    public void indexAll() {
        for (Family family : familyRepository.findByPhotoSourceFolderIsNotNull()) {
            indexer.indexNow(family.getId());
        }
    }
}
