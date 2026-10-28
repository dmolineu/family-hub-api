package com.familyhub.demo.dto;

import java.time.Instant;

/**
 * The folder of existing photos shown in the slideshow.
 *
 * @param folder        null when none is set
 * @param skippedHeic   HEIC pictures found but not shown: they need converting first
 * @param indexing      the folder is being read right now
 * @param lastIndexedAt null until it has been read since the server started
 * @param error         why the last reading failed, if it did
 */
public record PhotoSourceResponse(String folder, long photoCount, int skippedHeic, boolean indexing,
                                  Instant lastIndexedAt, String error) {
}
