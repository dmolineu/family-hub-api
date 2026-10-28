package com.familyhub.demo.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A page of the photos in the family's photo folder, newest first, for the Photos
 * screen's grid.
 *
 * @param total every photo in the folder, for "Show more"
 */
public record FolderPhotoPageResponse(List<Item> items, long total, int page, int size) {

    /**
     * One photo of the folder.
     *
     * @param name       its file name, for people browsing the grid with a screen reader
     * @param modifiedAt when the file last changed on the share
     */
    public record Item(UUID id, String name, Instant modifiedAt) {
    }
}
