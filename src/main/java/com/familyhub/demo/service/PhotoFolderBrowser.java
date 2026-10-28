package com.familyhub.demo.service;

import com.familyhub.demo.dto.PhotoFolderListingResponse;
import com.familyhub.demo.dto.PhotoFolderListingResponse.Folder;
import com.familyhub.demo.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lists the folders under the photo roots, so a parent can pick the photo folder by
 * tapping through them instead of typing a path. Only folders under the roots can be
 * listed (symbolic links resolved, as when choosing one); links themselves are left
 * out, and so are hidden folders and the ones a NAS adds, as when reading a share.
 */
@Service
@RequiredArgsConstructor
public class PhotoFolderBrowser {
    static final int MAX_FOLDERS = 500;

    private final PhotoFiles photoFiles;

    /**
     * A folder's folders and how many photos it holds; the roots themselves for none.
     *
     * @throws BadRequestException for a folder outside the roots, missing or unreadable (400)
     */
    public PhotoFolderListingResponse list(String folder) {
        if (folder == null || folder.isBlank()) {
            List<Folder> roots = photoFiles.roots().stream()
                    .filter(root -> Files.isDirectory(Path.of(root)))
                    .map(root -> new Folder(nameOf(Path.of(root)), root))
                    .toList();
            return new PhotoFolderListingResponse(null, null, roots, 0, false);
        }
        Path path = photoFiles.validateReadable(folder);
        List<Folder> folders = new ArrayList<>();
        int photos = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()) {
                    if (!FolderPhotoIndexer.isSkippedFolder(name)) {
                        folders.add(new Folder(name, entry.toString()));
                    }
                } else if (attributes.isRegularFile() && FolderPhotoIndexer.isPhotoFile(name)) {
                    photos++;
                }
            }
        } catch (IOException e) {
            throw new BadRequestException("The server can't read " + path + ".");
        }
        folders.sort(Comparator.comparing(Folder::name, String.CASE_INSENSITIVE_ORDER));
        boolean more = folders.size() > MAX_FOLDERS;
        List<Folder> shown = more ? List.copyOf(folders.subList(0, MAX_FOLDERS)) : List.copyOf(folders);
        return new PhotoFolderListingResponse(path.toString(), parentOf(path), shown, photos, more);
    }

    /** Up from a root leads back to the list of roots. */
    private String parentOf(Path path) {
        if (photoFiles.roots().contains(path.toString()) || path.getParent() == null) {
            return "";
        }
        return path.getParent().toString();
    }

    private static String nameOf(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }
}
