package com.familyhub.demo.service;

import com.familyhub.demo.config.PhotoStorageProperties;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.Family;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

/**
 * Photo files on disk. A family's folder must sit under one of the configured roots;
 * file names are made here (never taken from a request), so nothing a family types
 * can reach outside its folder.
 */
@Component
@RequiredArgsConstructor
public class PhotoFiles {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private final PhotoStorageProperties properties;
    private final SecureRandom random = new SecureRandom();

    /** The family's folder: its own choice, or the server's default. */
    public Path folderFor(Family family) {
        String folder = family.getPhotoFolder() == null ? properties.getDefaultFolder() : family.getPhotoFolder();
        return Path.of(folder).toAbsolutePath().normalize();
    }

    /**
     * Where shrunk copies for the family are kept: its own folder in the configured
     * cache (a local volume, so a NAS photo share holds only photos), or a hidden
     * ".cache" inside the family's photo folder when no cache is configured.
     */
    public Path cacheFolderFor(Family family) {
        String cache = properties.getCacheFolder();
        if (cache == null || cache.isBlank()) {
            return folderFor(family).resolve(".cache");
        }
        return Path.of(cache.trim()).toAbsolutePath().normalize().resolve(family.getId().toString());
    }

    /** The roots photos may be stored under, as absolute, tidied paths. */
    public List<String> roots() {
        return properties.getRoots().stream()
                .map(root -> Path.of(root).toAbsolutePath().normalize().toString())
                .toList();
    }

    /**
     * Checks a folder a family chose: under an allowed root (symbolic links resolved),
     * created if missing, and writable by the server.
     *
     * @throws BadRequestException naming what is wrong (400)
     */
    public Path validate(String folder) {
        if (folder == null || folder.isBlank() || !Path.of(folder.trim()).isAbsolute()) {
            throw new BadRequestException("Give the full path of a folder, starting with /.");
        }
        Path path = Path.of(folder.trim()).normalize();
        if (!underARoot(path)) {
            throw outsideRoots();
        }
        try {
            Files.createDirectories(path);
            if (!underARoot(path.toRealPath())) {
                throw outsideRoots();
            }
            Path probe = Files.createTempFile(path, ".familyhub-write-test", ".tmp");
            Files.delete(probe);
        } catch (IOException e) {
            throw new BadRequestException("The server can't write to " + path + ".");
        }
        return path;
    }

    /**
     * Checks a folder of existing photos a family chose to show: under an allowed root
     * (symbolic links resolved), there, and readable. It is never written to.
     *
     * @throws BadRequestException naming what is wrong (400)
     */
    public Path validateReadable(String folder) {
        if (folder == null || folder.isBlank() || !Path.of(folder.trim()).isAbsolute()) {
            throw new BadRequestException("Give the full path of a folder, starting with /.");
        }
        Path path = Path.of(folder.trim()).normalize();
        if (!underARoot(path)) {
            throw outsideRoots();
        }
        if (!Files.isDirectory(path)) {
            throw new BadRequestException("The server can't find a folder at " + path + ".");
        }
        try {
            if (!underARoot(path.toRealPath())) {
                throw outsideRoots();
            }
        } catch (IOException e) {
            throw new BadRequestException("The server can't find a folder at " + path + ".");
        }
        if (!Files.isReadable(path)) {
            throw new BadRequestException("The server can't read " + path + ".");
        }
        return path;
    }

    /**
     * Writes a photo into a folder under a name a person browsing the share can read,
     * "2026/2026-10-27_17-45-12_ab12cd34.jpg", and returns that name. Written to a
     * temporary file first, so a half-written photo is never seen.
     */
    public String write(Path folder, byte[] bytes, String contentType, Instant takenAt, ZoneId zone) {
        String name = takenAt.atZone(zone).getYear() + "/" + STAMP.format(takenAt.atZone(zone)) + "_"
                + HexFormat.of().formatHex(randomBytes()) + (contentType.equals("image/png") ? ".png" : ".jpg");
        Path target = folder.resolve(name);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some network shares cannot rename atomically; a plain rename still never
                // leaves a half-written photo under the real name.
                Files.move(temporary, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write photo " + target, e);
        }
        return name;
    }

    /** A stored photo's bytes. */
    public byte[] read(Path folder, String fileName) {
        try {
            return Files.readAllBytes(folder.resolve(fileName));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read photo " + fileName, e);
        }
    }

    /** Copies a photo between folders under the same name, checking every byte arrived. */
    public void copy(Path from, Path to, String fileName) {
        Path source = from.resolve(fileName);
        Path target = to.resolve(fileName);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(target) != Files.size(source)) {
                throw new IOException("size differs after copying");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not copy photo " + fileName, e);
        }
    }

    /** Removes a photo file if it is there; a missing file is not an error. */
    public void deleteQuietly(Path folder, String fileName) {
        try {
            Files.deleteIfExists(folder.resolve(fileName));
        } catch (IOException ignored) {
            // Left behind on a share that refused; harmless, and the database no longer points at it.
        }
    }

    private boolean underARoot(Path path) {
        for (String root : properties.getRoots()) {
            Path rootPath = Path.of(root).toAbsolutePath().normalize();
            if (path.startsWith(rootPath)) {
                return true;
            }
            try {
                if (Files.exists(rootPath) && path.startsWith(rootPath.toRealPath())) {
                    return true;
                }
            } catch (IOException ignored) {
                // An unreadable root cannot hold the folder.
            }
        }
        return false;
    }

    private BadRequestException outsideRoots() {
        return new BadRequestException("Photos can go in a folder under " + String.join(" or ", roots())
                + " (the folders mounted into the server for photos).");
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        return bytes;
    }
}
