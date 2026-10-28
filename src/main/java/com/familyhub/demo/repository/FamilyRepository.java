package com.familyhub.demo.repository;

import com.familyhub.demo.model.Family;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FamilyRepository extends JpaRepository<Family, UUID> {
    boolean existsByUsername(String username);
    Optional<Family> findByUsername(String username);

    /** Families that show a folder of existing photos, for the daily re-read. */
    List<Family> findByPhotoSourceFolderIsNotNull();
}
