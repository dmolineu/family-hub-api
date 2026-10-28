package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.model.FamilyPhoto;
import com.familyhub.demo.repository.FamilyPhotoRepository;
import com.familyhub.demo.service.PhotoFileMigrator;
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
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Photos live as files in a folder the family chooses (under the roots the server
 * allows); the database keeps their details and thumbnails.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class PhotoStorageIntegrationTest {

    private static final String PASSWORD = "TestPassword123!";
    private static final String PIN = "2468";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FamilyPhotoRepository photoRepository;

    @Autowired
    private PhotoFileMigrator migrator;

    /** As configured; on macOS java.io.tmpdir ends in "/", so it can hold "//". */
    @Value("${photos.roots}")
    private String configuredRoot;

    /** The root as the server reports it: absolute and tidied. */
    private String root() {
        return Path.of(configuredRoot).toAbsolutePath().normalize().toString();
    }

    private record Family(String token, String parentId, String kidId) {}

    @Test
    void anUploadIsAFileInTheFamilysFolderAndDeletingRemovesIt() throws Exception {
        Family f = register();
        byte[] photo = jpeg(640, 480);

        String id = upload(f, photo);

        FamilyPhoto stored = photoRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(stored.getData()).isNull();
        Path file = Path.of(folderOf(f)).resolve(stored.getFileName());
        assertThat(file).exists();
        assertThat(Files.readAllBytes(file)).isEqualTo(photo);
        // Named so a person browsing the share can tell what it is.
        assertThat(stored.getFileName()).matches("\\d{4}/\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_[0-9a-f]{8}\\.jpg");

        mockMvc.perform(get("/api/photos/{id}/content", id).header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(photo));

        mockMvc.perform(delete("/api/photos/{id}", id).header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isNoContent());
        assertThat(file).doesNotExist();
    }

    @Test
    void theStorageSettingShowsTheFolderAndWhereItMayGo() throws Exception {
        Family f = register();
        mockMvc.perform(get("/api/photos/storage").header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.folder").value(root() + "/library"))
                .andExpect(jsonPath("$.data.roots[0]").value(root()))
                .andExpect(jsonPath("$.data.moving").value(false));
    }

    @Test
    void movingTheLibraryNeedsAParentsPinAndAFolderTheServerAllows() throws Exception {
        Family f = register();
        String elsewhere = Files.createTempDirectory("not-a-root").toString();

        mockMvc.perform(put("/api/photos/storage").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(move(f.parentId(), "0000", root() + "/nas")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/photos/storage").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(move(f.kidId(), PIN, root() + "/nas")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/photos/storage").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(move(f.parentId(), PIN, elsewhere)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(root())));
        mockMvc.perform(put("/api/photos/storage").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(move(f.parentId(), PIN, root() + "/../escape")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void movingTheLibraryCopiesEveryPhotoThenSwitchesAndRemovesTheOldCopies() throws Exception {
        Family f = register();
        byte[] first = jpeg(320, 200);
        byte[] second = jpeg(200, 320);
        String one = upload(f, first);
        String two = upload(f, second);
        String oldFolder = folderOf(f);
        String newFolder = root() + "/nas-" + UUID.randomUUID();

        mockMvc.perform(put("/api/photos/storage").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(move(f.parentId(), PIN, newFolder)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.moving").value(true));

        awaitIdle(f);
        mockMvc.perform(get("/api/photos/storage").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data.folder").value(newFolder))
                .andExpect(jsonPath("$.data.lastMove.moved").value(2))
                .andExpect(jsonPath("$.data.lastMove.error").doesNotExist());

        for (String id : List.of(one, two)) {
            FamilyPhoto stored = photoRepository.findById(UUID.fromString(id)).orElseThrow();
            assertThat(Path.of(newFolder).resolve(stored.getFileName())).exists();
            assertThat(Path.of(oldFolder).resolve(stored.getFileName())).doesNotExist();
        }
        mockMvc.perform(get("/api/photos/{id}/content", one).header("Authorization", "Bearer " + f.token()))
                .andExpect(content().bytes(first));
    }

    @Test
    void aPhotoStillInTheDatabaseIsServedAndMovedToAFileAtStartup() throws Exception {
        Family f = register();
        byte[] photo = jpeg(300, 300);
        String id = upload(f, photo);
        // As uploads were stored before this version: the bytes in the row, no file.
        FamilyPhoto stored = photoRepository.findById(UUID.fromString(id)).orElseThrow();
        Files.delete(Path.of(folderOf(f)).resolve(stored.getFileName()));
        stored.setData(photo);
        stored.setFileName(null);
        photoRepository.save(stored);

        mockMvc.perform(get("/api/photos/{id}/content", id).header("Authorization", "Bearer " + f.token()))
                .andExpect(content().bytes(photo));

        migrator.migrate();

        FamilyPhoto migrated = photoRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(migrated.getData()).isNull();
        assertThat(Path.of(folderOf(f)).resolve(migrated.getFileName())).exists();
        mockMvc.perform(get("/api/photos/{id}/content", id).header("Authorization", "Bearer " + f.token()))
                .andExpect(content().bytes(photo));
    }

    @Test
    void aFolderTheServerCannotWriteToIsRefused() throws Exception {
        Family f = register();
        Path readOnly = Files.createDirectories(Path.of(root(), "read-only-" + UUID.randomUUID()));
        Files.setPosixFilePermissions(readOnly, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        try {
            mockMvc.perform(put("/api/photos/storage").header("Authorization", "Bearer " + f.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(move(f.parentId(), PIN, readOnly.toString())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("write")));
        } finally {
            Files.setPosixFilePermissions(readOnly, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        }
    }

    // ------------------------------------------------------------- helpers

    private String folderOf(Family f) throws Exception {
        return JsonPath.read(mockMvc.perform(get("/api/photos/storage").header("Authorization", "Bearer " + f.token()))
                .andReturn().getResponse().getContentAsString(), "$.data.folder");
    }

    private void awaitIdle(Family f) throws Exception {
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

    private static String move(String approverId, String pin, String folder) {
        return """
                {"approverMemberId": "%s", "pin": "%s", "folder": "%s"}""".formatted(approverId, pin, folder);
    }

    private String upload(Family f, byte[] photo) throws Exception {
        String body = mockMvc.perform(post("/api/photos").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(photo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        g.setColor(new Color(0x2f, 0x24, 0x1d));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(0xff, 0xc4, 0x25));
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(picture, "jpg", out);
        return out.toByteArray();
    }

    private Family register() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s", "familyName": "Photo Family",
                                 "members": [{"name": "Mom", "color": "coral"}, {"name": "Maya", "color": "teal"}],
                                 "timezone": "America/Los_Angeles", "starterRecipes": false}
                                """.formatted("ps_" + UUID.randomUUID().toString().substring(0, 8), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data.family.members[*].id");
        Family f = new Family(JsonPath.read(body, "$.data.token"), ids.get(0), ids.get(1));
        mockMvc.perform(put("/api/family/members/{id}/role", f.kidId()).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"KID\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/family/members/{id}/pin", f.parentId()).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"" + PASSWORD + "\", \"pin\": \"" + PIN + "\"}"))
                .andExpect(status().isNoContent());
        return f;
    }

}
