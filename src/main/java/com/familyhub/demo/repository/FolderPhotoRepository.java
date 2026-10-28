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

    /**
     * A random handful of a family's folder photos, for one showing of the slideshow.
     * The ids come back as text: H2 (the dev profile) returns a native query's UUID
     * column as raw bytes, and both databases spell a UUID cast to text the same way.
     */
    @Query(value = "select cast(id as varchar) from folder_photo where family_id = :familyId "
            + "order by random() limit :count", nativeQuery = true)
    List<String> sampleIds(@Param("familyId") UUID familyId, @Param("count") int count);

    @Modifying(flushAutomatically = true)
    @Query("delete from FolderPhoto p where p.family = :family")
    void deleteByFamily(@Param("family") Family family);
}
