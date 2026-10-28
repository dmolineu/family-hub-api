package com.familyhub.demo.service;

/**
 * Reads the EXIF orientation tag (0x0112) from a JPEG: 1 is upright, 6 means the
 * camera was turned a quarter clockwise, and so on. Just enough of the format to find
 * that one tag; anything unexpected reads as 1, so a picture is never refused for it.
 */
final class ExifOrientation {
    private static final int ORIENTATION_TAG = 0x0112;

    private ExifOrientation() {}

    static int read(byte[] jpeg) {
        try {
            if (jpeg.length < 4 || u8(jpeg, 0) != 0xFF || u8(jpeg, 1) != 0xD8) {
                return 1;
            }
            int offset = 2;
            while (offset + 4 <= jpeg.length && u8(jpeg, offset) == 0xFF) {
                int marker = u8(jpeg, offset + 1);
                int length = (u8(jpeg, offset + 2) << 8) | u8(jpeg, offset + 3);
                if (marker == 0xDA || length < 2) {
                    return 1; // image data starts: no EXIF before it
                }
                if (marker == 0xE1 && length >= 8 && "Exif".equals(new String(jpeg, offset + 4, 4,
                        java.nio.charset.StandardCharsets.US_ASCII))) {
                    return fromTiff(jpeg, offset + 10);
                }
                offset += 2 + length;
            }
        } catch (RuntimeException e) {
            return 1;
        }
        return 1;
    }

    private static int fromTiff(byte[] bytes, int tiff) {
        boolean little = bytes[tiff] == 'I';
        int ifd = tiff + u32(bytes, tiff + 4, little);
        int entries = u16(bytes, ifd, little);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (u16(bytes, entry, little) == ORIENTATION_TAG) {
                int value = u16(bytes, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static int u16(byte[] b, int i, boolean little) {
        return little ? u8(b, i) | (u8(b, i + 1) << 8) : (u8(b, i) << 8) | u8(b, i + 1);
    }

    private static int u32(byte[] b, int i, boolean little) {
        return little
                ? u16(b, i, true) | (u16(b, i + 2, true) << 16)
                : (u16(b, i, false) << 16) | u16(b, i + 2, false);
    }
}
