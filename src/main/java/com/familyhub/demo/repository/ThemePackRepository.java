package com.familyhub.demo.repository;

import com.familyhub.demo.model.ThemePack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Theme packs are always read through the owning family, never by id alone. */
public interface ThemePackRepository extends JpaRepository<ThemePack, UUID> {

    List<ThemePack> findByFamilyIdOrderByCreatedAtAscNameAsc(UUID familyId);

    Optional<ThemePack> findByIdAndFamilyId(UUID id, UUID familyId);

    boolean existsByFamilyIdAndNameIgnoreCase(UUID familyId, String name);

    @Modifying
    @Query("DELETE FROM ThemePack t WHERE t.family.id = :familyId")
    void deleteByFamilyId(@Param("familyId") UUID familyId);
}
