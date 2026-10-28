package com.familyhub.demo.service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Turns an HEIC picture (an iPhone's usual format) into a JPEG the rest of the photo
 * code can read. The JVM has no HEIC decoder, so this is the one place that knows how.
 */
public interface HeicDecoder {
    /** Whether this server can decode HEIC at all; without it HEIC pictures are skipped. */
    boolean available();

    /**
     * The picture as an upright JPEG.
     *
     * @throws IOException when it cannot be decoded
     */
    byte[] toJpeg(Path heic) throws IOException;
}
