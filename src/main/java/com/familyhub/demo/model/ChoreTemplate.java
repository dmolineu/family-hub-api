package com.familyhub.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "chore_template")
@Getter
@Setter
public class ChoreTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    /** Null means the chore is for anyone; whoever completes it gets the credit. */
    @ManyToOne
    @JoinColumn(name = "assigned_to_member_id")
    private FamilyMember assignedToMember;

    @Column(nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChoreCadence cadence;

    @Column(nullable = false)
    private LocalDate activeFrom;

    /** For WEEKLY chores: the days it is due; empty means once a week on any day. */
    @Convert(converter = DayOfWeekSetConverter.class)
    @Column(name = "days_of_week", length = 70)
    private Set<DayOfWeek> daysOfWeek = EnumSet.noneOf(DayOfWeek.class);

    public Set<DayOfWeek> getDaysOfWeek() {
        return daysOfWeek == null ? EnumSet.noneOf(DayOfWeek.class) : daysOfWeek;
    }

    /** True for a weekly chore pinned to specific days (each day is its own period). */
    public boolean isDueOnSpecificDays() {
        return cadence == ChoreCadence.WEEKLY && !getDaysOfWeek().isEmpty();
    }

    private LocalDateTime archivedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
