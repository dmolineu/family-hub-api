package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
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
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browsing the folders under the photo roots, so a parent can pick the photo folder by
 * tapping through it instead of typing a path: folders only, never outside the roots.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class PhotoFolderBrowserIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Value("${photos.roots}")
    private String rawRoot;

    private Path root() {
        return Path.of(rawRoot).toAbsolutePath().normalize();
    }

    private String register() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "TestPassword123!", "familyName": "Browse Family",
                                 "members": [{"name": "Mom", "color": "coral"}],
                                 "timezone": "America/Los_Angeles", "starterRecipes": false}
                                """.formatted("pb_" + UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.token");
    }

    /** A share as a NAS leaves it: photos in folders, and the NAS's own folders beside them. */
    private Path share() throws Exception {
        Files.createDirectories(root());
        Path share = Files.createDirectories(root().resolve("share-" + UUID.randomUUID()));
        Path photos = Files.createDirectories(share.resolve("Photos"));
        Files.write(photos.resolve("beach.jpg"), new byte[] {1});
        Files.write(photos.resolve("IMG_0002.PNG"), new byte[] {1});
        Files.write(photos.resolve("IMG_0003.HEIC"), new byte[] {1});
        Files.writeString(photos.resolve("notes.txt"), "not a photo");
        Files.write(photos.resolve(".hidden.jpg"), new byte[] {1});
        Files.createDirectories(photos.resolve("2026"));
        Files.createDirectories(share.resolve("archive"));
        Files.createDirectories(share.resolve("@eaDir"));
        Files.createDirectories(share.resolve("#recycle"));
        Files.createDirectories(share.resolve(".hidden"));
        Files.createSymbolicLink(share.resolve("escape"), Path.of("/etc"));
        return share;
    }

    private ResultActions browse(String token, String path) throws Exception {
        var request = get("/api/photos/folders").header("Authorization", "Bearer " + token);
        if (path != null) {
            request = request.param("path", path);
        }
        return mockMvc.perform(request);
    }

    @Test
    void startsAtTheFoldersMountedForPhotos() throws Exception {
        String token = register();
        Files.createDirectories(root());

        browse(token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path").value(nullValue()))
                .andExpect(jsonPath("$.data.parent").value(nullValue()))
                .andExpect(jsonPath("$.data.folders[*].path").value(hasItem(root().toString())));
    }

    @Test
    void listsAFoldersFoldersAndCountsItsPhotosLeavingOutTheNasOwnFolders() throws Exception {
        String token = register();
        Path share = share();

        browse(token, share.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path").value(share.toString()))
                .andExpect(jsonPath("$.data.parent").value(root().toString()))
                // Sorted as a person reads them; no @eaDir, #recycle, hidden folders or links.
                .andExpect(jsonPath("$.data.folders[*].name").value(contains("archive", "Photos")))
                .andExpect(jsonPath("$.data.folders[1].path").value(share.resolve("Photos").toString()))
                .andExpect(jsonPath("$.data.photos").value(0));

        browse(token, share.resolve("Photos").toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parent").value(share.toString()))
                .andExpect(jsonPath("$.data.folders[*].name").value(contains("2026")))
                // The JPEG, the PNG and the HEIC; not the text file or the hidden picture.
                .andExpect(jsonPath("$.data.photos").value(3));
    }

    @Test
    void fromARootGoingUpLeadsBackToTheListOfRoots() throws Exception {
        String token = register();
        Files.createDirectories(root());

        browse(token, root().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parent").value(""));
    }

    @Test
    void nothingOutsideTheRootsCanBeListed() throws Exception {
        String token = register();
        Path share = share();

        browse(token, "/etc").andExpect(status().isBadRequest());
        browse(token, share + "/../../../../etc").andExpect(status().isBadRequest());
        // A link inside the roots that points outside them is refused too.
        browse(token, share.resolve("escape").toString()).andExpect(status().isBadRequest());
        browse(token, "photos").andExpect(status().isBadRequest());
        browse(token, share.resolve("missing").toString()).andExpect(status().isBadRequest());
    }

    @Test
    void onlyASignedInFamilyCanBrowse() throws Exception {
        mockMvc.perform(get("/api/photos/folders")).andExpect(status().isUnauthorized());
    }
}
