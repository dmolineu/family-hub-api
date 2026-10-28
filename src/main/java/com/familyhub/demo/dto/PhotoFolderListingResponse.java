package com.familyhub.demo.dto;

import java.util.List;

/**
 * One folder under the photo roots, for picking a folder by tapping through them.
 *
 * @param path    the folder listed; null for the list of roots itself
 * @param parent  where "up" leads: a folder, "" for the list of roots, or null at the top
 * @param folders the folders inside, sorted by name (at most 500)
 * @param photos  how many photos sit directly in this folder
 * @param more    whether there were more folders than listed
 */
public record PhotoFolderListingResponse(String path, String parent, List<Folder> folders, int photos,
                                         boolean more) {

    /** A folder to open. */
    public record Folder(String name, String path) {
    }
}
