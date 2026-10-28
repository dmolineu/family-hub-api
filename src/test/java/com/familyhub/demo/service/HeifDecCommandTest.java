package com.familyhub.demo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HeifDecCommandTest {
    @TempDir
    Path temp;

    /**
     * A stand-in for heif-dec run as a shell script: it answers `--version` like the real
     * one, and `body` handles `<command> --quality 90 <in> <out>`.
     */
    private String script(String body) throws IOException {
        Path script = temp.resolve("fake-heif-dec-" + System.nanoTime());
        Files.writeString(script, "#!/bin/sh\n"
                + "if [ \"$1\" = --version ]; then echo 'libheif: 1.21.2'; exit 0; fi\n"
                + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script.toString();
    }

    private Path heic() throws IOException {
        Path file = temp.resolve("IMG_0001.HEIC");
        Files.write(file, new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p'});
        return file;
    }

    @Test
    void decodesThroughTheCommandIntoAJpeg() throws Exception {
        Path jpeg = temp.resolve("decoded.jpg");
        Files.write(jpeg, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3});
        HeifDecCommand decoder = new HeifDecCommand(script("cp '" + jpeg + "' \"$4\""), Duration.ofSeconds(10));

        assertThat(decoder.available()).isTrue();
        assertThat(decoder.toJpeg(heic())).isEqualTo(Files.readAllBytes(jpeg));
    }

    @Test
    void isUnavailableWhenTheCommandIsMissingOrSwitchedOff() {
        assertThat(new HeifDecCommand(temp.resolve("no-such-heif-dec").toString(), Duration.ofSeconds(5))
                .available()).isFalse();
        assertThat(new HeifDecCommand("-", Duration.ofSeconds(5)).available()).isFalse();
        assertThat(new HeifDecCommand("", Duration.ofSeconds(5)).available()).isFalse();
    }

    @Test
    void aPictureItCannotDecodeIsAnIOException() throws Exception {
        HeifDecCommand decoder = new HeifDecCommand(script("echo 'bad file' >&2; exit 1"), Duration.ofSeconds(10));

        assertThatThrownBy(() -> decoder.toJpeg(heic())).isInstanceOf(IOException.class)
                .hasMessageContaining("bad file");
    }

    @Test
    void aDecodeThatHangsIsStopped() throws Exception {
        HeifDecCommand decoder = new HeifDecCommand(script("sleep 30"), Duration.ofMillis(500));

        long started = System.nanoTime();
        assertThatThrownBy(() -> decoder.toJpeg(heic())).isInstanceOf(IOException.class)
                .hasMessageContaining("took too long");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void theRealDecoderTurnsAnIphoneStyleRotatedPhotoUprightOnce() throws Exception {
        HeifDecCommand decoder = new HeifDecCommand("heif-dec", Duration.ofSeconds(30));
        assumeTrue(decoder.available(), "heif-dec is not installed here (it is in the server image and CI)");
        Path file = temp.resolve("rotated.heic");
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/photos/rotated.heic"))) {
            Files.copy(in, file);
        }

        byte[] jpeg = decoder.toJpeg(file);
        // Stored 400x200 and marked to turn a quarter: upright it is 200x400, and the
        // screen shrinker must not turn it again.
        BufferedImage upright = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(upright.getWidth()).isEqualTo(200);
        assertThat(upright.getHeight()).isEqualTo(400);
        BufferedImage shown = ImageIO.read(new ByteArrayInputStream(new PictureShrinker().forScreen(jpeg)));
        assertThat(shown.getHeight()).isGreaterThan(shown.getWidth());
    }
}
