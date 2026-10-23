package com.familyhub.demo.service;

import com.familyhub.demo.dto.ThemePackPreview;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives a {@link ThemePackPreview} from a pack document: the card colours
 * straight from the tokens, and a 320x180 JPEG thumbnail of the picture named by
 * {@code slots.thumbnail}, or of the {@code background} image. SVG and WebP are
 * skipped (Java cannot decode them); nothing here ever fails an import.
 */
@Service
public class ThemePackPreviewService {

    public static final int THUMBNAIL_WIDTH = 320;
    public static final int THUMBNAIL_HEIGHT = 180;
    static final int MAX_THUMBNAIL_CHARS = 64 * 1024;
    private static final float JPEG_QUALITY = 0.72f;
    private static final Pattern DECODABLE_DATA_URL =
            Pattern.compile("^data:image/(png|jpe?g|gif);base64,([A-Za-z0-9+/=]+)$");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ThemePackPreview preview(String document) {
        JsonNode root;
        try {
            root = objectMapper.readTree(document);
        } catch (RuntimeException e) {
            return ThemePackPreview.empty();
        }
        if (root == null || !root.isObject()) {
            return ThemePackPreview.empty();
        }
        JsonNode tokens = root.path("tokens");
        return new ThemePackPreview(
                token(tokens, "--background"),
                token(tokens, "--card"),
                token(tokens, "--primary"),
                token(tokens, "--foreground"),
                token(tokens, "--accent"),
                thumbnail(root)
        );
    }

    private static String token(JsonNode tokens, String name) {
        JsonNode value = tokens.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String thumbnail(JsonNode root) {
        JsonNode images = root.path("images");
        if (!images.isObject()) {
            return null;
        }
        String key = root.path("slots").path("thumbnail").asText(null);
        JsonNode picture = key != null ? images.get(key) : null;
        if (picture == null) {
            picture = images.get("background");
        }
        if (picture == null || !picture.isTextual()) {
            return null;
        }
        try {
            return shrink(picture.asText());
        } catch (Exception e) {
            return null;
        }
    }

    /** Cover-crops to 16:9, scales to the thumbnail size and re-encodes as JPEG. */
    private static String shrink(String dataUrl) throws Exception {
        Matcher m = DECODABLE_DATA_URL.matcher(dataUrl);
        if (!m.matches()) {
            return null;
        }
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(m.group(2))));
        if (source == null) {
            return null;
        }
        double scale = Math.max(
                (double) THUMBNAIL_WIDTH / source.getWidth(),
                (double) THUMBNAIL_HEIGHT / source.getHeight());
        int scaledWidth = (int) Math.ceil(source.getWidth() * scale);
        int scaledHeight = (int) Math.ceil(source.getHeight() * scale);
        BufferedImage target = new BufferedImage(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        g.drawImage(source,
                (THUMBNAIL_WIDTH - scaledWidth) / 2, (THUMBNAIL_HEIGHT - scaledHeight) / 2,
                scaledWidth, scaledHeight, null);
        g.dispose();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(target, null, null), params);
        } finally {
            writer.dispose();
        }
        String encoded = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        return encoded.length() <= MAX_THUMBNAIL_CHARS ? encoded : null;
    }
}
