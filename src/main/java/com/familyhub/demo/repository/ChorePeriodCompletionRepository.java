package com.familyhub.demo.repository;

import com.familyhub.demo.model.Family;
import org.springframework.data.jpa.repository.Modifying;
import com.familyhub.demo.model.ChorePeriodCompletion;
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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ChorePeriodCompletion c where c.choreTemplate in (select t from ChoreTemplate t where t.family = :family)")
    void deleteByFamily(@Param("family") Family family);
}
