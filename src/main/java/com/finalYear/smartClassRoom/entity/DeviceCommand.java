package com.finalYear.smartClassRoom.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Tracks the complete lifecycle of classroom device commands:
 * CREATED -> DISPATCHED -> ACKNOWLEDGED -> VERIFIED (or FAILED, EXPIRED, SUPERSEDED).
 */
@Entity
@Table(
        name = "device_commands",
        indexes = {
                @Index(name = "idx_cmd_classroom", columnList = "classroom_id"),
                @Index(name = "idx_cmd_status", columnList = "status"),
                @Index(name = "idx_cmd_target", columnList = "target_type")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "command_id", nullable = false, unique = true, length = 64)
    private String commandId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "classroom_id", nullable = false)
    private Classroom classroom;

    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType; // FAN, LIGHT, PROJECTOR, ALL

    @Column(name = "target_device_id", length = 100)
    private String targetDeviceId;

    @Column(name = "action", nullable = false, length = 20)
    private String action; // ON, OFF

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private CommandStatus status = CommandStatus.CREATED;

    @Column(name = "issued_by", nullable = false, length = 100)
    private String issuedBy; // "AUTOMATION_ENGINE", user email, etc.

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "reason", length = 500)
    private String reason;

    public enum CommandStatus {
        CREATED,
        DISPATCHED,
        ACKNOWLEDGED,
        VERIFIED,
        FAILED,
        EXPIRED,
        SUPERSEDED
    }
}
