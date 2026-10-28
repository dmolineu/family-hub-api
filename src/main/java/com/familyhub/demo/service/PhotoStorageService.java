package com.familyhub.demo.service;

import com.familyhub.demo.dto.PhotoFolderRequest;
import com.familyhub.demo.dto.PhotoStorageResponse;
import com.familyhub.demo.exception.ConflictException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.repository.FamilyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * The family's photo folder: where it is, where it may go, and moving it (a parent's
 * PIN, since the photos are the family's and the folder is usually a shared drive).
 */
@Service
@RequiredArgsConstructor
public class PhotoStorageService {
    private final FamilyRepository familyRepository;
    private final PhotoFiles photoFiles;
    private final PhotoMoveTracker tracker;
    private final PhotoMover mover;
    private final ParentPinService parentPinService;

    /** Where the photos live now. */
    public PhotoStorageResponse status(Family principal) {
        Family family = familyRepository.findById(principal.getId()).orElseThrow();
        return new PhotoStorageResponse(
                photoFiles.folderFor(family).toString(),
                photoFiles.roots(),
                tracker.isMoving(family.getId()),
                tracker.lastResult(family.getId()).orElse(null));
    }

    /**
     * Starts moving the photos to another folder.
     *
     * @throws org.springframework.security.access.AccessDeniedException for a kid, a wrong or locked PIN (403)
     * @throws com.familyhub.demo.exception.BadRequestException for a folder outside the roots or not writable (400)
     * @throws ConflictException when a move is already running (409)
     */
    public PhotoStorageResponse move(Family principal, PhotoFolderRequest request) {
        Family family = familyRepository.findById(principal.getId()).orElseThrow();
        parentPinService.verify(family, request.approverMemberId(), request.pin());
        Path to = photoFiles.validate(request.folder());
        if (to.equals(photoFiles.folderFor(family))) {
            return status(family);
        }
        if (!tracker.start(family.getId())) {
            throw new ConflictException("The photos are already moving. Wait for that to finish.");
        }
        mover.move(family.getId(), to);
        return status(family);
    }
}
