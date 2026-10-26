package com.familyhub.demo.repository;

import com.familyhub.demo.model.Family;
import org.springframework.data.jpa.repository.Modifying;
import com.familyhub.demo.model.ChorePeriodCompletion;
import com.familyhub.demo.model.ChoreCompletionStatus;
import com.familyhub.demo.model.ChoreTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChorePeriodCompletionRepository extends JpaRepository<ChorePeriodCompletion, UUID> {
    @Query("""
            select c
            from ChorePeriodCompletion c
            where c.choreTemplate.id in :templateIds
              and c.periodStartDate = :periodStart
              and c.periodEndDate = :periodEnd
            """)
    List<ChorePeriodCompletion> findByTemplateIdsAndPeriod(
            @Param("templateIds") List<UUID> templateIds,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd
    );

    Optional<ChorePeriodCompletion> findByChoreTemplateAndPeriodStartDateAndPeriodEndDate(
            ChoreTemplate choreTemplate,
            LocalDate periodStartDate,
            LocalDate periodEndDate
    );

    @Query("select c from ChorePeriodCompletion c join fetch c.choreTemplate t where t.family = :family")
    List<ChorePeriodCompletion> findByFamily(@Param("family") Family family);

    /** A family's completions in one status, oldest first (the approvals queue). */
    @Query("select c from ChorePeriodCompletion c join fetch c.choreTemplate t "
            + "where t.family = :family and c.status = :status order by c.completedAt, c.id")
    List<ChorePeriodCompletion> findByFamilyAndStatus(
            @Param("family") Family family,
            @Param("status") ChoreCompletionStatus status
    );

    /** How many of a family's completions are in one status. */
    @Query("select count(c) from ChorePeriodCompletion c where c.choreTemplate.family = :family "
            + "and c.status = :status")
    long countByFamilyAndStatus(@Param("family") Family family, @Param("status") ChoreCompletionStatus status);

    /** One completion, only if it belongs to the family. */
    @Query("select c from ChorePeriodCompletion c join fetch c.choreTemplate t "
            + "where c.id = :id and t.family = :family")
    Optional<ChorePeriodCompletion> findByIdAndFamily(@Param("id") UUID id, @Param("family") Family family);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ChorePeriodCompletion c where c.choreTemplate in (select t from ChoreTemplate t where t.family = :family)")
    void deleteByFamily(@Param("family") Family family);
}
