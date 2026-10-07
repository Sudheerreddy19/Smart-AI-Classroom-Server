package com.finalYear.smartClassRoom.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "holidays",
        indexes = {
                @Index(name = "idx_holiday_date",     columnList = "holiday_date"),
                @Index(name = "idx_holiday_type",     columnList = "type"),
                @Index(name = "idx_holiday_semester", columnList = "semester_number")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@EqualsAndHashCode(of = "id")
public class Holiday implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private HolidayType type;

    @Column(length = 500)
    private String description;

    /**
     * null  → applies to ALL semesters (e.g. national holidays)
     * non-null → applies only to that specific semester number (1–8)
     */
    @Column(name = "semester_number")
    private Integer semesterNumber;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "created_by_user_id",
            foreignKey = @ForeignKey(name = "fk_holiday_created_by")
    )
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ─── Holiday Type Enum ────────────────────────────────────────────────────

    public enum HolidayType {
        /** Central / State government declared holiday */
        NATIONAL,
        /** Public holiday (Diwali, Eid, Holi, etc.) */
        PUBLIC,
        /** College-specific closure (founder's day, sports day, etc.) */
        COLLEGE,
        /** Exam period — classes suspended */
        EXAM,
        /** Restricted holiday — optional leave */
        RESTRICTED
    }
}
