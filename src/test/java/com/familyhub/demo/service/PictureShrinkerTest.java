package com.familyhub.demo.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PictureShrinkerTest {

    private final PictureShrinker shrinker = new PictureShrinker();

    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(picture, "jpg", out);
        return out.toByteArray();
    }

    /** The JPEG with an EXIF segment saying how the camera was held, as phones write it. */
    private static byte[] withOrientation(byte[] jpeg, int orientation, boolean littleEndian) {
        byte[] tiff = littleEndian
                ? new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0,
                        (byte) orientation, 0, 0, 0, 0, 0, 0, 0}
                : new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1,
                        0, (byte) orientation, 0, 0, 0, 0, 0, 0};
        byte[] exif = new byte[6 + tiff.length];
        System.arraycopy(new byte[] {'E', 'x', 'i', 'f', 0, 0}, 0, exif, 0, 6);
        System.arraycopy(tiff, 0, exif, 6, tiff.length);
        int length = exif.length + 2;
        byte[] result = new byte[jpeg.length + 2 + length];
        result[0] = (byte) 0xFF;
        result[1] = (byte) 0xD8;
        result[2] = (byte) 0xFF;
        result[3] = (byte) 0xE1;
        result[4] = (byte) (length >> 8);
        result[5] = (byte) length;
        System.arraycopy(exif, 0, result, 6, exif.length);
        System.arraycopy(jpeg, 2, result, 6 + exif.length, jpeg.length - 2);
        return result;
    }

    private static BufferedImage read(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void readsTheOrientationInEitherByteOrderAndUprightWithoutOne() throws Exception {
        byte[] plain = jpeg(40, 20);
        assertThat(ExifOrientation.read(plain)).isEqualTo(1);
        assertThat(ExifOrientation.read(withOrientation(plain, 6, false))).isEqualTo(6);
        assertThat(ExifOrientation.read(withOrientation(plain, 8, true))).isEqualTo(8);
        assertThat(ExifOrientation.read(new byte[] {1, 2, 3})).isEqualTo(1);
    }

    @Test
    void shrinksALargePictureToTheScreenKeepingItsShape() throws Exception {
        BufferedImage shown = read(shrinker.forScreen(jpeg(4000, 3000)));
        assertThat(shown.getWidth()).isEqualTo(1920);
        assertThat(shown.getHeight()).isEqualTo(1440);
    }

    @Test
    void turnsAPhotoTakenSidewaysTheRightWayUp() throws Exception {
        BufferedImage shown = read(shrinker.forScreen(withOrientation(jpeg(400, 200), 6, false)));
        assertThat(shown.getWidth()).isEqualTo(200);
        assertThat(shown.getHeight()).isEqualTo(400);
    }

    @Test
    void movesPixelsTheWayEachOrientationSays() {
        BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        // Rotating a quarter clockwise puts the top-left pixel at the top right.
        BufferedImage rotated = PictureShrinker.orient(source, 6);
        assertThat(rotated.getWidth()).isEqualTo(2);
        assertThat(rotated.getRGB(1, 0)).isEqualTo(Color.RED.getRGB());
        // Upside down puts it at the bottom right.
        assertThat(PictureShrinker.orient(source, 3).getRGB(2, 1)).isEqualTo(Color.RED.getRGB());
        // A quarter anticlockwise puts it at the bottom left.
        assertThat(PictureShrinker.orient(source, 8).getRGB(0, 2)).isEqualTo(Color.RED.getRGB());
    }
}
