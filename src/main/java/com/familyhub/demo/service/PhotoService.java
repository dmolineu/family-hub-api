package com.familyhub.demo.service;

import com.familyhub.demo.dto.PhotoResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ConflictException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyPhoto;
import com.familyhub.demo.repository.FamilyPhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The family's screensaver photos. Only JPEG and PNG are accepted, checked by their
 * bytes rather than the declared type, and decoded once to read the size and make a
 * thumbnail; a file that does not decode is refused. Size, pixel count and library
 * size are capped so one upload cannot exhaust memory on a Raspberry Pi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PhotoService {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final long MAX_PIXELS = 40_000_000L;
    static final int MAX_PHOTOS = 2000;
    static final int THUMBNAIL_EDGE = 480;

    private static final String UNREADABLE = "Photos must be JPEG or PNG files.";

    private final FamilyPhotoRepository familyPhotoRepository;
    private final PhotoFiles photoFiles;
    private final PhotoMoveTracker moveTracker;
    private final Clock clock;

    /** The family's photos, newest first. */
    public List<PhotoResponse> list(Family family) {
        return familyPhotoRepository.findSummariesByFamily(family);
    }

    /**
     * Stores an uploaded photo and its thumbnail.
     *
     * @throws BadRequestException for an empty, oversized, unreadable or enormous image,
     *                             or a full library
     */
    @Transactional
    public PhotoResponse upload(Family family, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException("The photo was empty.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new BadRequestException("Photos can be at most 8 MB. Try a smaller one.");
        }
        String contentType = detectType(bytes);
        if (familyPhotoRepository.countByFamily(family) >= MAX_PHOTOS) {
            throw new BadRequestException("The photo library is full (" + MAX_PHOTOS
                    + " photos). Delete some first.");
        }
        Decoded decoded = decode(bytes);
        if (moveTracker.isMoving(family.getId())) {
            throw new ConflictException("The photos are moving to their new folder. Try again in a moment.");
        }

        // The file first, so a row never points at a photo that is not there.
        Instant now = Instant.now(clock);
        Path folder = photoFiles.folderFor(family);
        String fileName = photoFiles.write(folder, bytes, contentType, now, zone(family));

        FamilyPhoto photo = new FamilyPhoto();
        photo.setFamily(family);
        photo.setContentType(contentType);
        photo.setWidth(decoded.width());
        photo.setHeight(decoded.height());
        photo.setSizeBytes(bytes.length);
        photo.setFileName(fileName);
        photo.setThumbnail(thumbnail(decoded.image()));
        photo.setCreatedAt(now);
        FamilyPhoto saved;
        try {
            saved = familyPhotoRepository.save(photo);
        } catch (RuntimeException e) {
            photoFiles.deleteQuietly(folder, fileName);
            throw e;
        }
        log.info("Photo uploaded, photoId={}, familyId={}, bytes={}", saved.getId(), family.getId(), bytes.length);
        return toResponse(saved);
    }

    /** The full photo, only for its own family. */
    public FamilyPhoto get(Family family, UUID id) {
        return familyPhotoRepository.findByIdAndFamily(id, family)
                .orElseThrow(() -> new ResourceNotFoundException("Photo", id));
    }

    /** The photo's bytes: from its file, or from the database for one not moved out yet. */
    public byte[] content(Family family, UUID id) {
        FamilyPhoto photo = get(family, id);
        if (photo.getFileName() == null) {
            return photo.getData();
        }
        return photoFiles.read(photoFiles.folderFor(managedFamily(photo)), photo.getFileName());
    }

    /** Removes a photo and its file, only for its own family. */
    @Transactional
    public void delete(Family family, UUID id) {
        FamilyPhoto photo = get(family, id);
        familyPhotoRepository.delete(photo);
        if (photo.getFileName() != null) {
            photoFiles.deleteQuietly(photoFiles.folderFor(managedFamily(photo)), photo.getFileName());
        }
        log.info("Photo deleted, photoId={}, familyId={}", id, family.getId());
    }

    /** The photo's family as stored now (the signed-in principal may predate a folder change). */
    private static Family managedFamily(FamilyPhoto photo) {
        return photo.getFamily();
    }

    private static ZoneId zone(Family family) {
        return ZoneId.of(FamilyTimezoneResolver.resolveStoredTimezoneOrDefault(family.getTimezone()));
    }

    private static String detectType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        throw new BadRequestException(UNREADABLE);
    }

    private record Decoded(BufferedImage image, int width, int height) {}

    /**
     * Reads the dimensions from the header first, refusing anything over the pixel
     * cap before decoding it, then decodes with subsampling: a thumbnail never needs
     * every pixel, and that keeps the decoded image small.
     */
    private static Decoded decode(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BadRequestException(UNREADABLE);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if ((long) width * height > MAX_PIXELS) {
                    throw new BadRequestException("That photo has too many pixels. Try a smaller one.");
                }
                int step = Math.max(1, Math.max(width, height) / (THUMBNAIL_EDGE * 2));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage image = reader.read(0, param);
                return new Decoded(image, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof BadRequestException bad) {
                throw bad;
            }
            throw new BadRequestException(UNREADABLE);
        }
    }

    private static byte[] thumbnail(BufferedImage source) {
        double scale = (double) THUMBNAIL_EDGE / Math.max(source.getWidth(), source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * Math.min(1.0, scale)));
        int height = Math.max(1, (int) Math.round(source.getHeight() * Math.min(1.0, scale)));
        if (scale > 1.0) {
            // Small originals are stretched up to the thumbnail edge so the grid is uniform.
            width = (int) Math.round(source.getWidth() * scale);
            height = (int) Math.round(source.getHeight() * scale);
        }
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.8f);
            writer.write(null, new IIOImage(scaled, null, null), param);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not write a thumbnail", ex);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static PhotoResponse toResponse(FamilyPhoto photo) {
        return new PhotoResponse(photo.getId(), photo.getContentType(), photo.getWidth(), photo.getHeight(),
                photo.getSizeBytes(), photo.getCreatedAt());
    }
}
