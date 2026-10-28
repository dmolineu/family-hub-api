package com.familyhub.demo.repository;

import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FolderPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The index of a family's source folder, always read through the family. */
public interface FolderPhotoRepository extends JpaRepository<FolderPhoto, UUID> {

    List<FolderPhoto> findByFamily(Family family);

    Optional<FolderPhoto> findByIdAndFamily(UUID id, Family family);

    long countByFamily(Family family);

    /** A random handful of a family's folder photos, for one showing of the slideshow. */
    @Query(value = "select id from folder_photo where family_id = :familyId order by random() limit :count",
            nativeQuery = true)
    List<UUID> sampleIds(@Param("familyId") UUID familyId, @Param("count") int count);

    @Modifying(flushAutomatically = true)
    @Query("delete from FolderPhoto p where p.family = :family")
    void deleteByFamily(@Param("family") Family family);
}
