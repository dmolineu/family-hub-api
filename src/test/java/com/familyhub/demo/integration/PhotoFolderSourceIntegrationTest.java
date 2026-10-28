package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.service.FolderPhotoIndexer;
import com.familyhub.demo.service.HeicDecoder;
import org.junit.jupiter.api.AfterEach;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
@SpringBootTest(properties = "photos.cache-folder=${java.io.tmpdir}/familyhub-test-photo-cache")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, PhotoFolderSourceIntegrationTest.SwitchableHeicDecoder.class})
@ActiveProfiles("test")
class PhotoFolderSourceIntegrationTest {

    /**
     * Stands in for heif-dec, which the server image has and this machine may not:
     * switched on, it "decodes" any HEIC into a large JPEG.
     */
    @TestConfiguration
    static class SwitchableHeicDecoder {
        static final AtomicBoolean AVAILABLE = new AtomicBoolean(false);
        /** How long each decode takes, and how many ran at once at the most. */
        static final AtomicLong DELAY_MS = new AtomicLong(0);
        static final AtomicInteger IN_FLIGHT = new AtomicInteger(0);
        static final AtomicInteger MAX_IN_FLIGHT = new AtomicInteger(0);

        @Bean
        @Primary
        HeicDecoder switchableHeicDecoder() {
            return new HeicDecoder() {
                @Override
                public boolean available() {
                    return AVAILABLE.get();
                }

                @Override
                public byte[] toJpeg(Path heic) throws java.io.IOException {
                    MAX_IN_FLIGHT.accumulateAndGet(IN_FLIGHT.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(DELAY_MS.get());
                        return image("jpg", 3024, 4032);
                    } catch (Exception e) {
                        throw new java.io.IOException(e);
                    } finally {
                        IN_FLIGHT.decrementAndGet();
                    }
                }
            };
        }
    }

    @AfterEach
    void noDecoder() {
        SwitchableHeicDecoder.AVAILABLE.set(false);
        SwitchableHeicDecoder.DELAY_MS.set(0);
        SwitchableHeicDecoder.MAX_IN_FLIGHT.set(0);
    }

    private static final String PASSWORD = "TestPassword123!";
    private static final String PIN = "2468";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FolderPhotoIndexer indexer;

    @Value("${photos.roots}")
    private String rawRoot;

    @Value("${photos.cache-folder}")
    private String cacheFolder;

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
    void heicPhotosJoinWhenTheServerCanDecodeThemAndAreShrunkIntoTheCache() throws Exception {
        SwitchableHeicDecoder.AVAILABLE.set(true);
        Family f = register();
        Path folder = library();

        mockMvc.perform(put("/api/photos/source").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(source(f.parentId(), PIN, folder.toString())))
                .andExpect(status().isAccepted());
        awaitIndexed(f);

        mockMvc.perform(get("/api/photos/source").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data.photoCount").value(3))
                .andExpect(jsonPath("$.data.skippedHeic").value(0));
        List<String> ids = JsonPath.read(mockMvc.perform(get("/api/photos/folder").param("sample", "50")
                        .header("Authorization", "Bearer " + f.token()))
                .andReturn().getResponse().getContentAsString(), "$.data[*]");
        for (String id : ids) {
            byte[] bytes = mockMvc.perform(get("/api/photos/folder/{id}/content", id)
                            .header("Authorization", "Bearer " + f.token()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/jpeg"))
                    .andReturn().getResponse().getContentAsByteArray();
            BufferedImage shown = ImageIO.read(new ByteArrayInputStream(bytes));
            assertThat(Math.max(shown.getWidth(), shown.getHeight())).isLessThanOrEqualTo(1920);
        }

        // The shrunk copies live in the server's cache, not among the family's photos.
        Path cache = Path.of(cacheFolder).resolve(familyId(f)).resolve("folder");
        try (var cached = Files.list(cache)) {
            assertThat(cached.count()).isEqualTo(3);
        }
        assertThat(folder.resolve(".cache")).doesNotExist();
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
    void photosUploadedInTheAppIntoTheShownFolderAppearOnce() throws Exception {
        // The family keeps uploads and the photos they drop in in one folder (the NAS
        // Photos share), which is also the folder the slideshow shows.
        Family f = register();
        Path shared = root().resolve("shared-" + UUID.randomUUID());
        mockMvc.perform(put("/api/photos/storage").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(source(f.parentId(), PIN, shared.toString())))
                .andExpect(status().isAccepted());
        awaitMoved(f);
        mockMvc.perform(post("/api/photos").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(image("jpg", 640, 480)))
                .andExpect(status().isCreated());
        Files.write(shared.resolve("dropped-in.jpg"), image("jpg", 800, 600));

        mockMvc.perform(put("/api/photos/source").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(source(f.parentId(), PIN, shared.toString())))
                .andExpect(status().isAccepted());
        awaitIndexed(f);

        // The upload is already in the slideshow as an upload; only the dropped-in one joins.
        mockMvc.perform(get("/api/photos/source").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data.photoCount").value(1));
    }

    @Test
    void theFoldersPhotosAreListedNewestFirstAPageAtATimeWithThumbnails() throws Exception {
        Family f = register();
        Path folder = library();
        Files.setLastModifiedTime(folder.resolve("beach.jpg"), FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(folder.resolve("2026/summer/IMG_0002.PNG"),
                FileTime.from(Instant.parse("2026-06-01T00:00:00Z")));
        mockMvc.perform(put("/api/photos/source").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(source(f.parentId(), PIN, folder.toString())))
                .andExpect(status().isAccepted());
        awaitIndexed(f);

        mockMvc.perform(get("/api/photos/folder/list").param("page", "0").param("size", "1")
                        .header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].name").value("IMG_0002.PNG"))
                .andExpect(jsonPath("$.data.items[0].modifiedAt").value("2026-06-01T00:00:00Z"));
        String second = mockMvc.perform(get("/api/photos/folder/list").param("page", "1").param("size", "1")
                        .header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].name").value("beach.jpg"))
                .andReturn().getResponse().getContentAsString();

        // A thumbnail for the grid, made from the screen copy and kept beside it.
        String id = JsonPath.read(second, "$.data.items[0].id");
        byte[] bytes = mockMvc.perform(get("/api/photos/folder/{id}/thumbnail", id)
                        .header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage thumbnail = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(Math.max(thumbnail.getWidth(), thumbnail.getHeight())).isLessThanOrEqualTo(480);
        Path cache = Path.of(cacheFolder).resolve(familyId(f)).resolve("folder");
        try (var cached = Files.list(cache)) {
            assertThat(cached.filter(file -> file.getFileName().toString().startsWith(id)).count()).isEqualTo(2);
        }
    }

    @Test
    void atMostTwoPicturesAreDecodedAtOnceWhenAGridAsksForMany() throws Exception {
        SwitchableHeicDecoder.AVAILABLE.set(true);
        SwitchableHeicDecoder.DELAY_MS.set(300);
        Family f = register();
        Path folder = Files.createDirectories(root().resolve("heic-src-" + UUID.randomUUID()));
        for (int i = 0; i < 5; i++) {
            Files.write(folder.resolve("IMG_10" + i + ".HEIC"), new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p'});
        }
        mockMvc.perform(put("/api/photos/source").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(source(f.parentId(), PIN, folder.toString())))
                .andExpect(status().isAccepted());
        awaitIndexed(f);
        List<String> ids = JsonPath.read(mockMvc.perform(get("/api/photos/folder/list")
                        .header("Authorization", "Bearer " + f.token()))
                .andReturn().getResponse().getContentAsString(), "$.data.items[*].id");
        assertThat(ids).hasSize(5);

        ExecutorService browser = Executors.newFixedThreadPool(5);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (String id : ids) {
                answers.add(browser.submit(() -> mockMvc.perform(get("/api/photos/folder/{id}/thumbnail", id)
                                .header("Authorization", "Bearer " + f.token()))
                        .andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> answer : answers) {
                assertThat(answer.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            browser.shutdownNow();
        }
        // Each decode is its own process on the server, outside the JVM's memory limit.
        assertThat(SwitchableHeicDecoder.MAX_IN_FLIGHT.get()).isEqualTo(2);
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
        mockMvc.perform(get("/api/photos/folder/{id}/thumbnail", id).header("Authorization", "Bearer " + other.token()))
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

    private void awaitMoved(Family f) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            Boolean moving = JsonPath.read(mockMvc.perform(get("/api/photos/storage")
                            .header("Authorization", "Bearer " + f.token()))
                    .andReturn().getResponse().getContentAsString(), "$.data.moving");
            if (!moving) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The move did not finish");
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
