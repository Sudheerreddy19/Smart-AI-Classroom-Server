package com.finalYear.smartClassRoom.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Durable manual teacher override that persists in PostgreSQL and survives server restarts.
 * While active, automatic sensor-based decisions cannot alter the device's forced state.
 */
@Entity
@Table(
        name = "device_overrides",
        indexes = {
                @Index(name = "idx_override_classroom_active", columnList = "classroom_id, is_active"),
                @Index(name = "idx_override_device_type", columnList = "device_type")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "classroom_id", nullable = false)
    private Classroom classroom;

    @Column(name = "device_type", nullable = false, length = 30)
    private String deviceType; // "FAN", "LIGHT", "PROJECTOR", "ALL"

    @Column(name = "forced_state", nullable = false, length = 20)
    private String forcedState; // "ON", "OFF"

    @Column(name = "set_by", nullable = false, length = 100)
    private String setBy;

    @Column(name = "reason", length = 500)
    private String reason;

    @CreationTimestamp
    @Column(name = "set_at", nullable = false, updatable = false)
    private LocalDateTime setAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
