package com.familyhub.demo.service;

import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FolderPhoto;
import com.familyhub.demo.repository.FamilyPhotoRepository;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.FolderPhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Keeps the index of a family's source folder: every JPEG and PNG in it, and every
 * HEIC when the server can decode them, found by walking the folder. Skips hidden
 * folders and the ones NAS software adds (Synology's "@eaDir" thumbnails, "#recycle"),
 * and the family's own uploads when they are kept in the same folder, since those are
 * already in the slideshow. Without a decoder, HEIC pictures are counted so the family
 * can be told why they are missing. Reading a large share takes a while, so it runs in
 * the background; how the last reading went is kept in memory.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FolderPhotoIndexer {
    private static final Set<String> PICTURES = Set.of("jpg", "jpeg", "png");
    private static final Set<String> HEIC = Set.of("heic", "heif");
    private static final int MAX_DEPTH = 32;

    private final FamilyRepository familyRepository;
    private final FolderPhotoRepository folderPhotoRepository;
    private final FamilyPhotoRepository familyPhotoRepository;
    private final PhotoFiles photoFiles;
    private final HeicDecoder heicDecoder;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Map<UUID, Status> statuses = new ConcurrentHashMap<>();

    /** How the folder's reading stands. */
    public record Status(boolean indexing, Instant lastIndexedAt, int skippedHeic, String error) {
        static final Status NEVER = new Status(false, null, 0, null);
    }

    public Status status(UUID familyId) {
        return statuses.getOrDefault(familyId, Status.NEVER);
    }

    /** Shows the family's folder as being read, before the background reading starts. */
    public void markIndexing(UUID familyId) {
        Status current = status(familyId);
        statuses.put(familyId, new Status(true, current.lastIndexedAt(), current.skippedHeic(), null));
    }

    /** Reads the family's folder in the background. */
    @Async
    public void index(UUID familyId) {
        indexNow(familyId);
    }

    /** Reads the family's folder now, bringing the index in line with what is there. */
    public void indexNow(UUID familyId) {
        Optional<Family> found = familyRepository.findById(familyId);
        if (found.isEmpty()) {
            statuses.remove(familyId);
            return;
        }
        Family family = found.get();
        if (family.getPhotoSourceFolder() == null) {
            transactions.executeWithoutResult(status -> folderPhotoRepository.deleteByFamily(family));
            statuses.put(familyId, new Status(false, Instant.now(clock), 0, null));
            return;
        }
        Path root = Path.of(family.getPhotoSourceFolder());
        markIndexing(familyId);
        try {
            Walk walk = walk(root, heicDecoder.available(), uploadsUnder(family, root));
            transactions.executeWithoutResult(status -> apply(family, walk.pictures()));
            statuses.put(familyId, new Status(false, Instant.now(clock), walk.heic(), null));
            log.info("Photo folder read, familyId={}, pictures={}, heicSkipped={}", familyId,
                    walk.pictures().size(), walk.heic());
        } catch (IOException | RuntimeException e) {
            Status previous = status(familyId);
            statuses.put(familyId, new Status(false, previous.lastIndexedAt(), previous.skippedHeic(),
                    "The folder could not be read: " + e.getMessage()));
            log.warn("Photo folder read failed, familyId={}, folder={}: {}", familyId, root, e.toString());
        }
    }

    private record Found(long size, Instant modifiedAt) {}

    private record Walk(Map<String, Found> pictures, int heic) {}

    /**
     * The family's uploads that sit inside the shown folder, as paths relative to it.
     * Real paths are compared, so a link or a differently spelt path to the same folder
     * still matches.
     */
    private Set<String> uploadsUnder(Family family, Path root) {
        try {
            Path storage = photoFiles.folderFor(family);
            if (!Files.isDirectory(storage)) {
                return Set.of();
            }
            Path source = root.toRealPath();
            Path realStorage = storage.toRealPath();
            if (!realStorage.startsWith(source)) {
                return Set.of();
            }
            Path prefix = source.relativize(realStorage);
            return familyPhotoRepository.findFileNamesByFamily(family).stream()
                    .map(name -> prefix.resolve(name).normalize().toString().replace('\\', '/'))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            return Set.of();
        }
    }

    private static Walk walk(Path root, boolean heicReadable, Set<String> uploads) throws IOException {
        Map<String, Found> pictures = new HashMap<>();
        int[] heic = {0};
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                boolean skipped = !dir.equals(root)
                        && (name.startsWith(".") || name.startsWith("@") || name.startsWith("#"));
                return skipped ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || file.getFileName().toString().startsWith(".")) {
                    return FileVisitResult.CONTINUE;
                }
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (PICTURES.contains(extension) || (heicReadable && HEIC.contains(extension))) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (relative.length() <= 1000 && !uploads.contains(relative)) {
                        pictures.put(relative, new Found(attrs.size(), attrs.lastModifiedTime().toInstant()));
                    }
                } else if (HEIC.contains(extension)) {
                    heic[0]++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE; // an unreadable file or folder is just left out
            }
        });
        return new Walk(pictures, heic[0]);
    }

    private void apply(Family family, Map<String, Found> pictures) {
        Map<String, FolderPhoto> known = new HashMap<>();
        for (FolderPhoto photo : folderPhotoRepository.findByFamily(family)) {
            known.put(photo.getRelativePath(), photo);
        }
        List<FolderPhoto> changed = new ArrayList<>();
        pictures.forEach((path, found) -> {
            FolderPhoto photo = known.remove(path);
            if (photo == null) {
                photo = new FolderPhoto();
                photo.setFamily(family);
                photo.setRelativePath(path);
            } else if (photo.getSizeBytes() == found.size() && photo.getModifiedAt().equals(found.modifiedAt())) {
                return;
            }
            photo.setSizeBytes(found.size());
            photo.setModifiedAt(found.modifiedAt());
            changed.add(photo);
        });
        folderPhotoRepository.saveAll(changed);
        folderPhotoRepository.deleteAll(known.values());
    }
}
