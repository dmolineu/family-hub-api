package com.familyhub.demo.service;

import com.familyhub.demo.config.PhotoStorageProperties;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoFilesTest {
    @TempDir
    Path temp;

    private Path root;
    private PhotoFiles files;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(temp.resolve("photos"));
        PhotoStorageProperties properties = new PhotoStorageProperties();
        properties.setRoots(List.of(root.toString()));
        properties.setDefaultFolder(root.resolve("library").toString());
        files = new PhotoFiles(properties);
    }

    @Test
    void aFamilyWithoutAChoiceUsesTheDefaultFolder() {
        Family family = new Family();
        assertThat(files.folderFor(family)).isEqualTo(root.resolve("library"));
        family.setPhotoFolder(root.resolve("nas").toString());
        assertThat(files.folderFor(family)).isEqualTo(root.resolve("nas"));
    }

    @Test
    void acceptsAndCreatesAFolderUnderARoot() {
        Path folder = files.validate(root.resolve("nas/family").toString());
        assertThat(folder).isDirectory().isEqualTo(root.resolve("nas/family"));
        // The write check leaves nothing behind.
        assertThat(folder.toFile().list()).isEmpty();
    }

    @Test
    void refusesRelativeFoldersFoldersOutsideTheRootsAndWaysOut() throws Exception {
        assertThatThrownBy(() -> files.validate("photos/nas")).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("full path");
        assertThatThrownBy(() -> files.validate(temp.resolve("elsewhere").toString()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining(root.toString());
        assertThatThrownBy(() -> files.validate(root + "/../elsewhere"))
                .isInstanceOf(BadRequestException.class);
        // A link inside a root that points outside it is outside it.
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.createSymbolicLink(root.resolve("sneaky"), outside);
        assertThatThrownBy(() -> files.validate(root.resolve("sneaky").toString()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void writesAPhotoUnderAReadableNameAndReadsCopiesAndDeletesIt() {
        Path library = files.validate(root.resolve("library").toString());
        byte[] bytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        Instant takenAt = Instant.parse("2026-10-28T01:05:09Z");

        String name = files.write(library, bytes, "image/jpeg", takenAt, ZoneId.of("America/Los_Angeles"));

        // 6:05:09 PM on 27 October in Los Angeles.
        assertThat(name).matches("2026/2026-10-27_18-05-09_[0-9a-f]{8}\\.jpg");
        assertThat(files.read(library, name)).isEqualTo(bytes);
        // No temporary file is left beside it.
        assertThat(library.resolve("2026").toFile().list()).containsExactly(Path.of(name).getFileName().toString());

        Path nas = files.validate(root.resolve("nas").toString());
        files.copy(library, nas, name);
        assertThat(files.read(nas, name)).isEqualTo(bytes);

        files.deleteQuietly(library, name);
        assertThat(library.resolve(name)).doesNotExist();
        files.deleteQuietly(library, name);
    }

    @Test
    void namesAPngAsAPng() {
        Path library = files.validate(root.resolve("library").toString());
        String name = files.write(library, new byte[] {1}, "image/png", Instant.EPOCH, ZoneId.of("UTC"));
        assertThat(name).endsWith(".png");
    }
}
