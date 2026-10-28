package com.familyhub.demo.dto;

import java.time.Instant;
import java.util.List;

/**
 * Where the family's photos live, where they may go, and how the last move went.
 *
 * @param roots    the folders the server may store photos under
 * @param moving   a move is copying photos right now
 * @param lastMove the last finished (or failed) move; null if none since the server started
 */
public record PhotoStorageResponse(String folder, List<String> roots, boolean moving, MoveResult lastMove) {

    /** How a move ended; on an error the photos stayed where they were. */
    public record MoveResult(String to, int moved, String error, Instant finishedAt) {
    }
}
