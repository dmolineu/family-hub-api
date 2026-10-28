package com.familyhub.demo.service;

import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

/**
 * Makes a picture from a family's own folder fit for the wall: at most 1920 px on its
 * longer side, turned the right way up (phones record that in an EXIF tag, which a
 * re-encoded copy would lose), and a JPEG. Decoded with subsampling, so a 48-megapixel
 * phone photo never needs its full size in memory on a Pi.
 */
@Component
public class PictureShrinker {
    static final int MAX_EDGE = 1920;

    /**
     * The picture for the screen.
     *
     * @throws IllegalArgumentException when the bytes are not a readable JPEG or PNG
     */
    public byte[] forScreen(byte[] bytes) {
        BufferedImage decoded = decode(bytes);
        BufferedImage scaled = scale(decoded);
        BufferedImage upright = orient(scaled, ExifOrientation.read(bytes));
        return jpeg(upright);
    }

    private static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Not a JPEG or PNG");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int longest = Math.max(reader.getWidth(0), reader.getHeight(0));
                int step = Math.max(1, longest / MAX_EDGE);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unreadable picture", e);
        }
    }

    private static BufferedImage scale(BufferedImage source) {
        double factor = Math.min(1.0, (double) MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        // A transparent PNG is shown on white rather than black.
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }

    /** Applies an EXIF orientation (1 to 8) by moving pixels; 1 is already upright. */
    static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return source;
        }
        int w = source.getWidth();
        int h = source.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage target = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = source.getRGB(x, y);
                switch (orientation) {
                    case 2 -> target.setRGB(w - 1 - x, y, rgb);
                    case 3 -> target.setRGB(w - 1 - x, h - 1 - y, rgb);
                    case 4 -> target.setRGB(x, h - 1 - y, rgb);
                    case 5 -> target.setRGB(y, x, rgb);
                    case 6 -> target.setRGB(h - 1 - y, x, rgb);
                    case 7 -> target.setRGB(h - 1 - y, w - 1 - x, rgb);
                    default -> target.setRGB(y, w - 1 - x, rgb);
                }
            }
        }
        return target;
    }

    private static byte[] jpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the picture", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
