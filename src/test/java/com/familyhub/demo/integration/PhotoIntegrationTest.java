package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Photos the family uploads for the screensaver: stored with a small thumbnail,
 * served only to the family that owns them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class PhotoIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static byte[] image(String format, int width, int height) throws Exception {
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        g.setColor(new Color(0x2f, 0x24, 0x1d));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(0xff, 0xc4, 0x25));
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(picture, format, out);
        return out.toByteArray();
    }

    private String registerAndToken() throws Exception {
        String username = "photo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "password123", "familyName": "Photo Family",
                                 "members": [{"name": "Parent", "color": "CORAL"}], "timezone": "America/Los_Angeles",
                                 "starterRecipes": false}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.token");
    }

    @Test
    void aFamilyUploadsListsViewsAndDeletesItsPhotos() throws Exception {
        String token = registerAndToken();
        String other = registerAndToken();
        byte[] jpeg = image("jpg", 1600, 900);

        String created = mockMvc.perform(post("/api/photos")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(jpeg))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.data.width").value(1600))
                .andExpect(jsonPath("$.data.height").value(900))
                .andExpect(jsonPath("$.data.sizeBytes").value(jpeg.length))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.data.id");

        mockMvc.perform(post("/api/photos")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.IMAGE_PNG)
                        .content(image("png", 400, 600)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contentType").value("image/png"));

        // Newest first.
        mockMvc.perform(get("/api/photos").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[1].id").value(id));

        byte[] content = mockMvc.perform(get("/api/photos/{id}/content", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(content).isEqualTo(jpeg);

        byte[] thumbnail = mockMvc.perform(get("/api/photos/{id}/thumbnail", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(thumbnail));
        assertThat(Math.max(thumb.getWidth(), thumb.getHeight())).isEqualTo(480);
        assertThat(thumb.getWidth()).isGreaterThan(thumb.getHeight());

        // Another family can neither see nor delete it.
        mockMvc.perform(get("/api/photos").header("Authorization", "Bearer " + other))
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/photos/{id}/content", id).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/photos/{id}", id).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/photos/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/photos/{id}/content", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/photos").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void anythingThatIsNotAReadablePhotoIsRefused() throws Exception {
        String token = registerAndToken();

        mockMvc.perform(post("/api/photos")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content("not a photo at all".getBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("JPEG or PNG")));

        mockMvc.perform(post("/api/photos")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(new byte[0]))
                .andExpect(status().isBadRequest());

        // Over the size cap, even with a JPEG signature.
        byte[] huge = new byte[8 * 1024 * 1024 + 1];
        huge[0] = (byte) 0xFF;
        huge[1] = (byte) 0xD8;
        huge[2] = (byte) 0xFF;
        mockMvc.perform(post("/api/photos")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(huge))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("8 MB")));
    }
}
