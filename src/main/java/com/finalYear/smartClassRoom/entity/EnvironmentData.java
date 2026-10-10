package com.finalYear.smartClassRoom.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "environment_data",
        indexes = {
                @Index(name = "idx_env_classroom", columnList = "classroom_id"),
                @Index(name = "idx_env_recorded_at", columnList = "recorded_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnvironmentData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "classroom_id", nullable = false)
    private Classroom classroom;
    private Double temperature;

    private Double humidity;

    @Column(name = "co2_level")
    private Double co2Level;

    @Column(name = "light_level")
    private Double lightLevel;

    @Column(name = "noise_level")
    private Double noiseLevel;

    @Column(name = "air_quality_index")
    private Double airQualityIndex;

    @Column(name = "room_state", length = 30)
    private String roomState; // ACTIVE or EMPTY (from PIR)

    @Column(name = "is_dark")
    private Boolean isDark; // true / false (Dark: YES/NO)

    @Column(name = "gas_leak")
    private Boolean gasLeak; // true / false (GasLeak: YES/NO)

    @Column(name = "gas_ppm")
    private Double gasPpm; // Gas sensor PPM reading

    @Column(name = "fan_status", length = 10)
    private String fanStatus; // ON or OFF

    @Column(name = "light_status", length = 10)
    private String lightStatus; // ON or OFF

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;
}