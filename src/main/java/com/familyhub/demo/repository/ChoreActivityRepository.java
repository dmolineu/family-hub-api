package com.familyhub.demo.repository;

import com.familyhub.demo.model.ChoreActivity;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** The chore log, newest first, and the points balances it adds up to. */
public interface ChoreActivityRepository extends JpaRepository<ChoreActivity, UUID> {

    /** Every line of a family's log, newest first. */
    List<ChoreActivity> findByFamilyOrderByOccurredAtDescIdDesc(Family family);

    /** One member's lines, newest first. */
    List<ChoreActivity> findByFamilyAndMemberOrderByOccurredAtDescIdDesc(Family family, FamilyMember member);

    /** Each member's points balance, as rows of (member id, sum of points). */
    @Query("select a.member.id, coalesce(sum(a.points), 0) from ChoreActivity a "
            + "where a.family = :family and a.member is not null group by a.member.id")
    List<Object[]> sumPointsByMember(@Param("family") Family family);

    /** One member's points balance: the sum of their lines. */
    @Query("select coalesce(sum(a.points), 0) from ChoreActivity a where a.member = :member")
    int sumPoints(@Param("member") FamilyMember member);

    /** Removes a family's whole log (restore replaces it). */
    @Modifying(flushAutomatically = true)
    @Query("delete from ChoreActivity a where a.family = :family")
    void deleteByFamily(@Param("family") Family family);
}
