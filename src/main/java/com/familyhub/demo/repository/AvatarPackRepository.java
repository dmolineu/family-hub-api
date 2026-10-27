package com.familyhub.demo.repository;

import com.familyhub.demo.model.AvatarPack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Avatar packs are always read through the owning family, never by id alone. */
public interface AvatarPackRepository extends JpaRepository<AvatarPack, UUID> {

    List<AvatarPack> findByFamilyIdOrderByCreatedAtAscNameAsc(UUID familyId);

    Optional<AvatarPack> findByIdAndFamilyId(UUID id, UUID familyId);

    boolean existsByFamilyIdAndNameIgnoreCase(UUID familyId, String name);

    @Modifying
    @Query("DELETE FROM AvatarPack a WHERE a.family.id = :familyId")
    void deleteByFamilyId(@Param("familyId") UUID familyId);
}
