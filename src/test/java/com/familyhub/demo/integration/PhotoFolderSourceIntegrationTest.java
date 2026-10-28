package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.service.FolderPhotoIndexer;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A folder of the family's existing photos (the NAS Photos share, say) shown in the
 * slideshow beside uploads: read-only, indexed in the background, sent shrunk.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class PhotoFolderSourceIntegrationTest {

    private static final String PASSWORD = "TestPassword123!";
    private static final String PIN = "2468";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FolderPhotoIndexer indexer;

    @Value("${photos.roots}")
    private String rawRoot;

    private Path root() {
        return Path.of(rawRoot).toAbsolutePath().normalize();
    }

    private record Family(String token, String parentId) {}

    /** A library as a Drive backup or a phone sync leaves it. */
    private Path library() throws Exception {
        Path folder = Files.createDirectories(root().resolve("library-src-" + UUID.randomUUID()));
        Files.write(folder.resolve("beach.jpg"), image("jpg", 2400, 1600));
        Files.createDirectories(folder.resolve("2026/summer"));
        Files.write(folder.resolve("2026/summer/IMG_0002.PNG"), image("png", 800, 600));
        Files.write(folder.resolve("2026/summer/IMG_0003.HEIC"), new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p'});
        Files.writeString(folder.resolve("notes.txt"), "not a photo");
        Files.createDirectories(folder.resolve("@eaDir/beach.jpg"));
        Files.write(folder.resolve("@eaDir/beach.jpg/SYNOPHOTO_THUMB_XL.jpg"), image("jpg", 100, 100));
        Files.createDirectories(folder.resolve(".hidden"));
        Files.write(folder.resolve(".hidden/secret.jpg"), image("jpg", 100, 100));
        return folder;
    }

    @Test
    void aFolderOfExistingPhotosJoinsTheSlideshowShrunkForTheScreen() throws Exception {
        Family f = register();
        Path folder = library();

        mockMvc.perform(put("/api/photos/source").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(source(f.parentId(), PIN, folder.toString())))
                .andExpect(status().isAccepted());
        awaitIndexed(f);

        mockMvc.perform(get("/api/photos/source").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data.folder").value(folder.toString()))
                .andExpect(jsonPath("$.data.photoCount").value(2))
                // HEIC needs converting first; counted so the family knows why they are missing.
                .andExpect(jsonPath("$.data.skippedHeic").value(1))
                .andExpect(jsonPath("$.data.lastIndexedAt").isNotEmpty());

        String sample = mockMvc.perform(get("/api/photos/folder").param("sample", "50")
                        .header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(sample, "$.data[*]");

        for (String id : ids) {
            byte[] bytes = mockMvc.perform(get("/api/photos/folder/{id}/content", id)
                            .header("Authorization", "Bearer " + f.token()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/jpeg"))
                    .andReturn().getResponse().getContentAsByteArray();
            BufferedImage shown = ImageIO.read(new ByteArrayInputStream(bytes));
            assertThat(Math.max(shown.getWidth(), shown.getHeight())).isLessThanOrEqualTo(1920);
        }

        // A photo removed from the share leaves the slideshow at the next index.
        Files.delete(folder.resolve("beach.jpg"));
        indexer.indexNow(UUID.fromString(familyId(f)));
        mockMvc.perform(get("/api/photos/folder").param("sample", "50")
                        .header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void settingTheFolderNeedsAParentsPinAndAReadableFolderUnderTheRoots() throws Exception {
        Family f = register();
        Path folder = library();
        Path outside = Files.createTempDirectory("photos-outside");

        mockMvc.perform(put("/api/photos/source").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(source(f.parentId(), "0000", folder.toString())))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/photos/source").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(source(f.parentId(), PIN, outside.toString())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/photos/source").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(source(f.parentId(), PIN, root().resolve("no-such-folder").toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("find")));
    }

    @Test
    void clearingTheFolderTakesItsPhotosOutOfTheSlideshow() throws Exception {
        Family f = register();
        mockMvc.perform(put("/api/photos/source").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(source(f.parentId(), PIN, library().toString())))
                .andExpect(status().isAccepted());
        awaitIndexed(f);

        mockMvc.perform(put("/api/photos/source").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(source(f.parentId(), PIN, "")))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/photos/source").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data.folder").doesNotExist())
                .andExpect(jsonPath("$.data.photoCount").value(0));
        mockMvc.perform(get("/api/photos/folder").param("sample", "50")
                        .header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void anotherFamilyCannotFetchTheFamilysFolderPhotos() throws Exception {
        Family f = register();
        Family other = register();
        mockMvc.perform(put("/api/photos/source").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(source(f.parentId(), PIN, library().toString())))
                .andExpect(status().isAccepted());
        awaitIndexed(f);
        String id = JsonPath.read(mockMvc.perform(get("/api/photos/folder").param("sample", "1")
                        .header("Authorization", "Bearer " + f.token()))
                .andReturn().getResponse().getContentAsString(), "$.data[0]");

        // Even when the other family shows a folder with the same file names.
        mockMvc.perform(put("/api/photos/source").header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(source(other.parentId(), PIN, library().toString())))
                .andExpect(status().isAccepted());
        awaitIndexed(other);

        mockMvc.perform(get("/api/photos/folder/{id}/content", id).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------- helpers

    private void awaitIndexed(Family f) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            String body = mockMvc.perform(get("/api/photos/source").header("Authorization", "Bearer " + f.token()))
                    .andReturn().getResponse().getContentAsString();
            Boolean indexing = JsonPath.read(body, "$.data.indexing");
            Object indexedAt = JsonPath.read(body, "$.data.lastIndexedAt");
            if (!indexing && indexedAt != null) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The folder was not indexed");
    }

    private String familyId(Family f) throws Exception {
        return JsonPath.read(mockMvc.perform(get("/api/family").header("Authorization", "Bearer " + f.token()))
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private static String source(String approverId, String pin, String folder) {
        return """
                {"approverMemberId": "%s", "pin": "%s", "folder": "%s"}""".formatted(approverId, pin, folder);
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        g.setColor(new Color(0x2f, 0x82, 0x7f));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(0xff, 0xd2, 0x3f));
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(picture, format, out);
        return out.toByteArray();
    }

    private Family register() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s", "familyName": "Folder Family",
                                 "members": [{"name": "Mom", "color": "coral"}],
                                 "timezone": "America/Los_Angeles", "starterRecipes": false}
                                """.formatted("pf_" + UUID.randomUUID().toString().substring(0, 8), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data.family.members[*].id");
        Family f = new Family(JsonPath.read(body, "$.data.token"), ids.getFirst());
        mockMvc.perform(put("/api/family/members/{id}/pin", f.parentId()).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"" + PASSWORD + "\", \"pin\": \"" + PIN + "\"}"))
                .andExpect(status().isNoContent());
        return f;
    }
}
