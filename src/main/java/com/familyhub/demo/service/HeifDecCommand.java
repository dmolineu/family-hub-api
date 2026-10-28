package com.familyhub.demo.service;

import com.familyhub.demo.config.PhotoStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Decodes HEIC with libheif's {@code heif-dec}, installed in the server image, run as
 * a separate process with a time limit so a damaged file can never hang or crash the
 * server. heif-dec applies the picture's stored rotation and resets its EXIF
 * orientation, so the JPEG it writes is already upright. libheif before 1.18 names the
 * same tool {@code heif-convert}, which the default falls back to.
 */
@Slf4j
@Component
public class HeifDecCommand implements HeicDecoder {
    private static final Duration DECODE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);
    private static final int MESSAGE_LIMIT = 300;

    private static final String DEFAULT_COMMAND = "heif-dec";
    private static final String OLDER_NAME = "heif-convert";

    private final List<String> candidates;
    private final Duration timeout;
    private volatile Boolean available;
    private volatile String command;

    /** The command configured in {@code photos.heic-decoder}. */
    @Autowired
    public HeifDecCommand(PhotoStorageProperties properties) {
        this(candidatesFor(properties.getHeicDecoder()), DECODE_TIMEOUT);
    }

    HeifDecCommand(String command, Duration timeout) {
        this(candidatesFor(command), timeout);
    }

    HeifDecCommand(List<String> candidates, Duration timeout) {
        this.candidates = List.copyOf(candidates);
        this.timeout = timeout;
    }

    /** The commands to try: the configured one, and for the default also its older name. */
    static List<String> candidatesFor(String configured) {
        String command = configured == null ? "" : configured.trim();
        if (command.isEmpty() || command.equals("-")) {
            return List.of();
        }
        return command.equals(DEFAULT_COMMAND) ? List.of(DEFAULT_COMMAND, OLDER_NAME) : List.of(command);
    }

    /** Asks each candidate for its version once; the first that answers is used. */
    @Override
    public boolean available() {
        Boolean known = available;
        if (known == null) {
            known = false;
            if (candidates.isEmpty()) {
                log.info("HEIC decoding is switched off; HEIC photos will be skipped");
            }
            for (String candidate : candidates) {
                if (probe(candidate)) {
                    command = candidate;
                    known = true;
                    break;
                }
            }
            available = known;
        }
        return known;
    }

    private static boolean probe(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            if (!process.waitFor(VERSION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                log.warn("HEIC decoder {} did not answer; HEIC photos will be skipped", command);
                return false;
            }
            String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean works = process.exitValue() == 0;
            if (works) {
                log.info("HEIC photos are decoded with {} {}", command, version.lines().findFirst().orElse(""));
            } else {
                log.warn("HEIC decoder {} failed ({}); HEIC photos will be skipped", command, version);
            }
            return works;
        } catch (IOException e) {
            log.info("No HEIC decoder ({}: {}); HEIC photos will be skipped", command, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public byte[] toJpeg(Path heic) throws IOException {
        if (!available()) {
            throw new IOException("This server cannot decode HEIC photos");
        }
        Path work = Files.createTempDirectory("heic-");
        try {
            Path out = work.resolve("photo.jpg");
            Path messages = work.resolve("messages.txt");
            // Run inside the temporary folder, so nothing the decoder writes lands elsewhere.
            Process process = new ProcessBuilder(command, "--quality", "90", heic.toString(), out.toString())
                    .directory(work.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(messages.toFile())
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Decoding " + heic.getFileName() + " took too long");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(out)) {
                throw new IOException("Could not decode " + heic.getFileName() + ": " + shortened(messages));
            }
            return Files.readAllBytes(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while decoding " + heic.getFileName(), e);
        } finally {
            deleteQuietly(work);
        }
    }

    private static String shortened(Path messages) {
        try {
            String text = Files.readString(messages, StandardCharsets.UTF_8).strip();
            return text.length() <= MESSAGE_LIMIT ? text : text.substring(0, MESSAGE_LIMIT) + "…";
        } catch (IOException e) {
            return "no details";
        }
    }

    private static void deleteQuietly(Path folder) {
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            // A leftover temporary folder is harmless; the OS clears /tmp.
        }
    }
}
