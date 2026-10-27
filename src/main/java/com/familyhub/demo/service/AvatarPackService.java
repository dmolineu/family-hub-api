package com.familyhub.demo.service;

import com.familyhub.demo.dto.AvatarPackSummaryResponse;
import com.familyhub.demo.dto.BackupDocument.AvatarPackBackup;
import com.familyhub.demo.exception.ConflictException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.AvatarPack;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.repository.AvatarPackRepository;
import com.familyhub.demo.repository.FamilyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Avatar packs are family data: imported once, offered in every member's avatar picker,
 * exported back as the same file and carried in the family backup. Choosing a character
 * copies its picture onto the member, so nothing here ever changes a member.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AvatarPackService {
    private final AvatarPackRepository avatarPackRepository;
    private final FamilyRepository familyRepository;
    private final AvatarPackDocumentValidator validator;

    /** A pack ready to be sent back as a download or to the picker. */
    public record ExportedPack(String name, String document) {}

    /** The family's packs, oldest first, without their pictures. */
    @Transactional(readOnly = true)
    public List<AvatarPackSummaryResponse> list(Family family) {
        return avatarPackRepository.findByFamilyIdOrderByCreatedAtAscNameAsc(family.getId()).stream()
                .map(AvatarPackService::summary)
                .toList();
    }

    /**
     * Validates and stores a pack.
     *
     * @throws ConflictException when the family already has a pack of that name (any case)
     */
    public AvatarPackSummaryResponse importPack(String document, Family principal) {
        ValidatedAvatarPack validated = validator.validate(document);
        if (avatarPackRepository.existsByFamilyIdAndNameIgnoreCase(principal.getId(), validated.name())) {
            throw new ConflictException("An avatar pack named \"" + validated.name() + "\" already exists");
        }
        return summary(save(validated, principal.getId()));
    }

    /** The pack's document exactly as imported. */
    @Transactional(readOnly = true)
    public ExportedPack document(UUID id, Family family) {
        AvatarPack pack = find(id, family);
        return new ExportedPack(pack.getName(), pack.getDocument());
    }

    /** Deletes a pack; members who chose one of its characters keep their copy. */
    public void delete(UUID id, Family family) {
        avatarPackRepository.delete(find(id, family));
    }

    // ------------------------------------------------------------------ backup

    /** Every pack of the family, for the backup file. */
    @Transactional(readOnly = true)
    public List<AvatarPackBackup> exportAll(Family family) {
        return avatarPackRepository.findByFamilyIdOrderByCreatedAtAscNameAsc(family.getId()).stream()
                .map(p -> new AvatarPackBackup(p.getName(), p.getDocument()))
                .toList();
    }

    /**
     * Replaces the family's packs with those of a backup. Each document is validated
     * again, so a tampered backup cannot smuggle in what an import would refuse.
     */
    public int replaceAll(List<AvatarPackBackup> backups, Family family) {
        avatarPackRepository.deleteByFamilyId(family.getId());
        avatarPackRepository.flush();
        if (backups == null || backups.isEmpty()) {
            return 0;
        }
        List<ValidatedAvatarPack> validated = new ArrayList<>();
        for (AvatarPackBackup backup : backups) {
            ValidatedAvatarPack pack = validator.validate(backup.document());
            if (validated.stream().anyMatch(v -> v.name().equalsIgnoreCase(pack.name()))) {
                throw new ConflictException("The backup holds two avatar packs named \"" + pack.name() + "\"");
            }
            validated.add(pack);
        }
        validated.forEach(pack -> save(pack, family.getId()));
        return validated.size();
    }

    // ----------------------------------------------------------------- helpers

    private AvatarPack find(UUID id, Family family) {
        return avatarPackRepository.findByIdAndFamilyId(id, family.getId())
                .orElseThrow(() -> new ResourceNotFoundException("AvatarPack", id));
    }

    private AvatarPack save(ValidatedAvatarPack validated, UUID familyId) {
        AvatarPack pack = new AvatarPack();
        pack.setFamily(familyRepository.getReferenceById(familyId));
        pack.setName(validated.name());
        pack.setCharacterCount(validated.characterCount());
        pack.setGroups(String.join("\n", validated.groups()));
        pack.setDocument(validated.document());
        return avatarPackRepository.save(pack);
    }

    private static AvatarPackSummaryResponse summary(AvatarPack pack) {
        return new AvatarPackSummaryResponse(
                pack.getId(),
                pack.getName(),
                pack.getCharacterCount(),
                Arrays.asList(pack.getGroups().split("\n")),
                pack.getDocument().getBytes(StandardCharsets.UTF_8).length,
                pack.getCreatedAt() == null ? null : pack.getCreatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
