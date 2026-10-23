package com.familyhub.demo.service;

import com.familyhub.demo.dto.BackupDocument.ThemePackBackup;
import com.familyhub.demo.dto.ThemePackSummaryResponse;
import com.familyhub.demo.exception.ConflictException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.ThemePack;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.ThemePackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Theme packs are family data: imported once, listed on every device, exported back
 * as the same file, and carried in the family backup. Which device shows which pack
 * is a device preference the server never sees.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ThemePackService {
    private final ThemePackRepository themePackRepository;
    private final FamilyRepository familyRepository;
    private final ThemePackDocumentValidator validator;

    /** A pack ready to be sent back as a download. */
    public record ExportedPack(String name, String document) {}

    @Transactional(readOnly = true)
    public List<ThemePackSummaryResponse> list(Family family) {
        return themePackRepository.findByFamilyIdOrderByCreatedAtAscNameAsc(family.getId()).stream()
                .map(ThemePackService::summary)
                .toList();
    }

    public ThemePackSummaryResponse importPack(String document, Family principal) {
        ValidatedThemePack validated = validator.validate(document);
        if (themePackRepository.existsByFamilyIdAndNameIgnoreCase(principal.getId(), validated.name())) {
            throw new ConflictException("A theme pack named \"" + validated.name() + "\" already exists");
        }
        return summary(save(validated, principal.getId()));
    }

    @Transactional(readOnly = true)
    public ExportedPack document(UUID id, Family family) {
        ThemePack pack = find(id, family);
        return new ExportedPack(pack.getName(), pack.getDocument());
    }

    public void delete(UUID id, Family family) {
        themePackRepository.delete(find(id, family));
    }

    // ------------------------------------------------------------------ backup

    /** Every pack of the family, for the backup file. */
    @Transactional(readOnly = true)
    public List<ThemePackBackup> exportAll(Family family) {
        return themePackRepository.findByFamilyIdOrderByCreatedAtAscNameAsc(family.getId()).stream()
                .map(p -> new ThemePackBackup(p.getName(), p.isDark(), p.getDocument()))
                .toList();
    }

    /**
     * Replaces the family's packs with those of a backup. Each document is validated
     * again, so a tampered backup cannot smuggle in what an import would refuse.
     */
    public int replaceAll(List<ThemePackBackup> backups, Family family) {
        themePackRepository.deleteByFamilyId(family.getId());
        themePackRepository.flush();
        if (backups == null || backups.isEmpty()) {
            return 0;
        }
        List<ValidatedThemePack> validated = new ArrayList<>();
        for (ThemePackBackup backup : backups) {
            ValidatedThemePack pack = validator.validate(backup.document());
            boolean duplicate = validated.stream().anyMatch(v -> v.name().equalsIgnoreCase(pack.name()));
            if (duplicate) {
                throw new ConflictException("The backup holds two theme packs named \"" + pack.name() + "\"");
            }
            validated.add(pack);
        }
        for (ValidatedThemePack pack : validated) {
            save(pack, family.getId());
        }
        return validated.size();
    }

    // ----------------------------------------------------------------- helpers

    private ThemePack find(UUID id, Family family) {
        return themePackRepository.findByIdAndFamilyId(id, family.getId())
                .orElseThrow(() -> new ResourceNotFoundException("ThemePack", id));
    }

    private ThemePack save(ValidatedThemePack validated, UUID familyId) {
        ThemePack pack = new ThemePack();
        pack.setFamily(familyRepository.getReferenceById(familyId));
        pack.setName(validated.name());
        pack.setDark(validated.dark());
        pack.setDocument(validated.document());
        return themePackRepository.save(pack);
    }

    private static ThemePackSummaryResponse summary(ThemePack pack) {
        return new ThemePackSummaryResponse(
                pack.getId(),
                pack.getName(),
                pack.isDark(),
                pack.getDocument().getBytes(StandardCharsets.UTF_8).length,
                pack.getCreatedAt() == null ? null : pack.getCreatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
