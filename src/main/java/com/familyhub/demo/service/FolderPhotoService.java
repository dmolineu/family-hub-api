package com.familyhub.demo.service;

import com.familyhub.demo.dto.FolderPhotoPageResponse;
import com.familyhub.demo.dto.PhotoSourceRequest;
import com.familyhub.demo.dto.PhotoSourceResponse;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FolderPhoto;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.FolderPhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * The folder of photos a family already has, shown in the slideshow beside uploads:
 * choosing it (a parent's PIN), its index, a random handful for each showing, each
 * picture shrunk for the screen, and the Photos screen's pages of thumbnails. The
 * folder is only ever read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FolderPhotoService {
    static final int MAX_SAMPLE = 500;
    /** Bigger files are left out of the slideshow rather than read into memory. */
    static final long MAX_SOURCE_BYTES = 60L * 1024 * 1024;
    static final int MAX_PAGE = 120;
    /**
     * Copies made at once. A grid of thumbnails asks for many pictures together, and an
     * HEIC one is decoded by its own process outside the JVM's memory limit, so on a Pi
     * or a small VM the rest wait their turn. Copies already in the cache never wait.
     */
    static final int MAX_DECODING = 2;

    private final FamilyRepository familyRepository;
    private final FolderPhotoRepository folderPhotoRepository;
    private final FolderPhotoIndexer indexer;
    private final PhotoFiles photoFiles;
    private final PictureShrinker shrinker;
    private final HeicDecoder heicDecoder;
    private final ParentPinService parentPinService;
    private final Semaphore decoding = new Semaphore(MAX_DECODING, true);

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
        return folderPhotoRepository.sampleIds(family.getId(), bounded);
    }

    /**
     * A page of the folder's photos, newest first (by when the file last changed), for
     * the Photos screen. Pages hold at most {@value #MAX_PAGE}.
     */
    @Transactional(readOnly = true)
    public FolderPhotoPageResponse list(Family principal, int page, int size) {
        Family family = managed(principal);
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.max(1, Math.min(MAX_PAGE, size));
        Page<FolderPhoto> found = folderPhotoRepository.findByFamily(family, PageRequest.of(boundedPage, boundedSize,
                Sort.by(Sort.Order.desc("modifiedAt"), Sort.Order.asc("relativePath"))));
        return new FolderPhotoPageResponse(
                found.map(photo -> new FolderPhotoPageResponse.Item(photo.getId(), fileName(photo.getRelativePath()),
                        photo.getModifiedAt())).toList(),
                found.getTotalElements(), boundedPage, boundedSize);
    }

    /**
     * One picture, at most 1920 px and upright, as a JPEG; an HEIC one is decoded first.
     * Shrunk copies are kept in the family's cache folder, so each is made once.
     *
     * @throws ResourceNotFoundException for a picture not in this family's index, or gone from the folder (404)
     */
    @Transactional(readOnly = true)
    public byte[] content(Family principal, UUID id) {
        return forScreen(locate(principal, id));
    }

    /**
     * One picture for the Photos screen's grid, at most 480 px, made from its screen copy
     * and kept beside it in the cache.
     *
     * @throws ResourceNotFoundException for a picture not in this family's index, or gone from the folder (404)
     */
    @Transactional(readOnly = true)
    public byte[] thumbnail(Family principal, UUID id) {
        Located photo = locate(principal, id);
        Path cached = photo.cachedAs("-thumb.jpg");
        try {
            if (Files.isRegularFile(cached)) {
                return Files.readAllBytes(cached);
            }
            byte[] thumbnail = shrinker.forThumbnail(forScreen(photo));
            cache(cached, thumbnail);
            return thumbnail;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Folder photo thumbnail unreadable, photoId={}: {}", id, e.toString());
            throw new ResourceNotFoundException("Photo", id);
        }
    }

    /** A picture of the family's folder: the file on the share and where its copies are kept. */
    private record Located(UUID id, Path file, Path cacheFolder, long modifiedMillis) {
        Path cachedAs(String suffix) {
            return cacheFolder.resolve(id + "-" + modifiedMillis + suffix);
        }
    }

    private Located locate(Family principal, UUID id) {
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
        return new Located(id, file, photoFiles.cacheFolderFor(family).resolve("folder"),
                photo.getModifiedAt().toEpochMilli());
    }

    /** The screen copy, from the cache or made now (two at a time at the most). */
    private byte[] forScreen(Located photo) {
        Path cached = photo.cachedAs(".jpg");
        try {
            if (Files.isRegularFile(cached)) {
                return Files.readAllBytes(cached);
            }
            decoding.acquireUninterruptibly();
            try {
                // Another request may have made it while this one waited.
                if (Files.isRegularFile(cached)) {
                    return Files.readAllBytes(cached);
                }
                if (Files.size(photo.file()) > MAX_SOURCE_BYTES) {
                    throw new ResourceNotFoundException("Photo", photo.id());
                }
                byte[] original = isHeic(photo.file()) ? heicDecoder.toJpeg(photo.file())
                        : Files.readAllBytes(photo.file());
                byte[] shrunk = shrinker.forScreen(original);
                cache(cached, shrunk);
                return shrunk;
            } finally {
                decoding.release();
            }
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Folder photo unreadable, photoId={}, file={}: {}", photo.id(), photo.file(), e.toString());
            throw new ResourceNotFoundException("Photo", photo.id());
        }
    }

    private static String fileName(String relativePath) {
        return Path.of(relativePath).getFileName().toString();
    }

    private static boolean isHeic(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".heic") || name.endsWith(".heif");
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
