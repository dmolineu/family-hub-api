package com.familyhub.demo.service;

import com.familyhub.demo.dto.PhotoSourceRequest;
import com.familyhub.demo.dto.PhotoSourceResponse;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FolderPhoto;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.FolderPhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * The folder of photos a family already has, shown in the slideshow beside uploads:
 * choosing it (a parent's PIN), its index, a random handful for each showing, and each
 * picture shrunk for the screen. The folder is only ever read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FolderPhotoService {
    static final int MAX_SAMPLE = 500;
    /** Bigger files are left out of the slideshow rather than read into memory. */
    static final long MAX_SOURCE_BYTES = 60L * 1024 * 1024;

    private final FamilyRepository familyRepository;
    private final FolderPhotoRepository folderPhotoRepository;
    private final FolderPhotoIndexer indexer;
    private final PhotoFiles photoFiles;
    private final PictureShrinker shrinker;
    private final ParentPinService parentPinService;

    /** The folder, how many pictures it holds, and how its last reading went. */
    @Transactional(readOnly = true)
    public PhotoSourceResponse status(Family principal) {
        Family family = managed(principal);
        FolderPhotoIndexer.Status status = indexer.status(family.getId());
        return new PhotoSourceResponse(family.getPhotoSourceFolder(), folderPhotoRepository.countByFamily(family),
                status.skippedHeic(), status.indexing(), status.lastIndexedAt(), status.error());
    }

    /**
     * Chooses the folder (read in the background) or, with an empty one, stops showing it.
     *
     * @throws org.springframework.security.access.AccessDeniedException for a kid, a wrong or locked PIN (403)
     * @throws com.familyhub.demo.exception.BadRequestException for a folder outside the roots, missing or unreadable (400)
     */
    public PhotoSourceResponse choose(Family principal, PhotoSourceRequest request) {
        Family family = managed(principal);
        parentPinService.verify(family, request.approverMemberId(), request.pin());
        String folder = request.folder() == null ? "" : request.folder().trim();
        if (folder.isEmpty()) {
            family.setPhotoSourceFolder(null);
            familyRepository.save(family);
            indexer.indexNow(family.getId());
            return status(family);
        }
        Path path = photoFiles.validateReadable(folder);
        family.setPhotoSourceFolder(path.toString());
        familyRepository.save(family);
        indexer.markIndexing(family.getId());
        indexer.index(family.getId());
        return status(family);
    }

    /** A random handful of the folder's pictures, for one showing of the slideshow. */
    @Transactional(readOnly = true)
    public List<String> sample(Family family, int count) {
        int bounded = Math.max(1, Math.min(MAX_SAMPLE, count));
        return folderPhotoRepository.sampleIds(family.getId(), bounded).stream().map(UUID::toString).toList();
    }

    /**
     * One picture, at most 1920 px and upright, as a JPEG. Shrunk copies are kept in a
     * hidden cache inside the family's own photo folder, so each is made once.
     *
     * @throws ResourceNotFoundException for a picture not in this family's index, or gone from the folder (404)
     */
    @Transactional(readOnly = true)
    public byte[] content(Family principal, UUID id) {
        Family family = managed(principal);
        FolderPhoto photo = folderPhotoRepository.findByIdAndFamily(id, family)
                .orElseThrow(() -> new ResourceNotFoundException("Photo", id));
        if (family.getPhotoSourceFolder() == null) {
            throw new ResourceNotFoundException("Photo", id);
        }
        Path folder = Path.of(family.getPhotoSourceFolder());
        Path file = folder.resolve(photo.getRelativePath()).normalize();
        if (!file.startsWith(folder) || !Files.isRegularFile(file)) {
            throw new ResourceNotFoundException("Photo", id);
        }
        Path cached = photoFiles.folderFor(family).resolve(".cache/folder")
                .resolve(id + "-" + photo.getModifiedAt().toEpochMilli() + ".jpg");
        try {
            if (Files.isRegularFile(cached)) {
                return Files.readAllBytes(cached);
            }
            if (Files.size(file) > MAX_SOURCE_BYTES) {
                throw new ResourceNotFoundException("Photo", id);
            }
            byte[] shrunk = shrinker.forScreen(Files.readAllBytes(file));
            cache(cached, shrunk);
            return shrunk;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Folder photo unreadable, photoId={}, file={}: {}", id, file, e.toString());
            throw new ResourceNotFoundException("Photo", id);
        }
    }

    private static void cache(Path cached, byte[] bytes) {
        try {
            Files.createDirectories(cached.getParent());
            Files.write(cached, bytes);
        } catch (IOException e) {
            // A read-only photo folder just means shrinking again next time.
        }
    }

    private Family managed(Family principal) {
        return familyRepository.findById(principal.getId()).orElseThrow();
    }
}
