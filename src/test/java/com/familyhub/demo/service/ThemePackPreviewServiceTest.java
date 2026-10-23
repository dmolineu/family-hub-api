package com.familyhub.demo.service;

import com.familyhub.demo.dto.ThemePackPreview;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ThemePackPreviewServiceTest {

    private final ThemePackPreviewService service = new ThemePackPreviewService();

    private static String pngDataUrl(int width, int height, Color colour) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static String doc(String tokens, String images, String slots) {
        return "{\"version\":1,\"name\":\"X\",\"dark\":false,\"tokens\":" + tokens
                + ",\"images\":" + images + ",\"slots\":" + slots + "}";
    }

    @Test
    void picksTheFiveCardColoursFromTheTokens() {
        ThemePackPreview preview = service.preview(doc(
                "{\"--background\":\"#2f241d99\",\"--card\":\"#3b2e25\",\"--primary\":\"#ffc425\","
                        + "\"--foreground\":\"#f7f1e8\",\"--accent\":\"#5a4838\"}",
                "{}", "{}"));

        assertThat(preview.background()).isEqualTo("#2f241d99");
        assertThat(preview.card()).isEqualTo("#3b2e25");
        assertThat(preview.primary()).isEqualTo("#ffc425");
        assertThat(preview.foreground()).isEqualTo("#f7f1e8");
        assertThat(preview.accent()).isEqualTo("#5a4838");
        assertThat(preview.thumbnail()).isNull();
    }

    @Test
    void leavesMissingColoursNullForTheClientToFillIn() {
        ThemePackPreview preview = service.preview(doc("{\"--primary\":\"#e3350d\"}", "{}", "{}"));
        assertThat(preview.primary()).isEqualTo("#e3350d");
        assertThat(preview.background()).isNull();
        assertThat(preview.card()).isNull();
    }

    @Test
    void shrinksTheBackgroundPictureIntoASmallJpegThumbnail() throws Exception {
        String big = pngDataUrl(1920, 1080, new Color(18, 26, 77));
        ThemePackPreview preview = service.preview(doc("{}", "{\"background\":\"" + big + "\"}", "{}"));

        assertThat(preview.thumbnail()).startsWith("data:image/jpeg;base64,");
        assertThat(preview.thumbnail().length()).isLessThan(64 * 1024);
        BufferedImage thumb = ImageIO.read(new java.io.ByteArrayInputStream(
                Base64.getDecoder().decode(preview.thumbnail().substring("data:image/jpeg;base64,".length()))));
        assertThat(thumb.getWidth()).isEqualTo(ThemePackPreviewService.THUMBNAIL_WIDTH);
        assertThat(thumb.getHeight()).isEqualTo(ThemePackPreviewService.THUMBNAIL_HEIGHT);
        // Still the backdrop's colour, not a blank.
        Color centre = new Color(thumb.getRGB(thumb.getWidth() / 2, thumb.getHeight() / 2));
        assertThat(centre.getBlue()).isGreaterThan(centre.getRed());
    }

    @Test
    void prefersAnExplicitThumbnailSlotAndCropsToCover() throws Exception {
        String banner = pngDataUrl(1000, 200, new Color(255, 203, 5));
        String backdrop = pngDataUrl(400, 400, new Color(0, 0, 0));
        ThemePackPreview preview = service.preview(doc("{}",
                "{\"background\":\"" + backdrop + "\",\"parade\":\"" + banner + "\"}",
                "{\"thumbnail\":\"parade\"}"));
        BufferedImage thumb = ImageIO.read(new java.io.ByteArrayInputStream(
                Base64.getDecoder().decode(preview.thumbnail().substring("data:image/jpeg;base64,".length()))));
        Color centre = new Color(thumb.getRGB(thumb.getWidth() / 2, thumb.getHeight() / 2));
        assertThat(centre.getRed()).isGreaterThan(200);
        assertThat(centre.getBlue()).isLessThan(60);
    }

    @Test
    void skipsPicturesJavaCannotDecodeAndNeverFails() {
        String svg = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString("<svg/>".getBytes());
        ThemePackPreview preview = service.preview(doc("{}", "{\"background\":\"" + svg + "\"}", "{}"));
        assertThat(preview.thumbnail()).isNull();

        String garbage = "data:image/png;base64,AAAA";
        assertThat(service.preview(doc("{}", "{\"background\":\"" + garbage + "\"}", "{}")).thumbnail()).isNull();

        assertThat(service.preview("not json").thumbnail()).isNull();
    }
}
