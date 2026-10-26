package com.familyhub.demo.repository;

import com.familyhub.demo.dto.PhotoResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Photos, listed without their bytes so a big library stays a small response. */
public interface FamilyPhotoRepository extends JpaRepository<FamilyPhoto, UUID> {

    /** A family's photos, newest first, as summaries (no image bytes loaded). */
    @Query("select new com.familyhub.demo.dto.PhotoResponse(p.id, p.contentType, p.width, p.height, "
            + "p.sizeBytes, p.createdAt) from FamilyPhoto p where p.family = :family "
            + "order by p.createdAt desc, p.id desc")
    List<PhotoResponse> findSummariesByFamily(@Param("family") Family family);

    /** One photo, only if it belongs to the family. */
    Optional<FamilyPhoto> findByIdAndFamily(UUID id, Family family);

    /** How many photos a family has (the library is capped). */
    long countByFamily(Family family);
}
