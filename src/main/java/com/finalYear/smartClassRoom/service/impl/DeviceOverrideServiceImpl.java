package com.finalYear.smartClassRoom.service.impl;

import com.finalYear.smartClassRoom.entity.Classroom;
import com.finalYear.smartClassRoom.entity.DeviceOverride;
import com.finalYear.smartClassRoom.repository.DeviceOverrideRepository;
import com.finalYear.smartClassRoom.service.DeviceOverrideService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceOverrideServiceImpl implements DeviceOverrideService {

    private final DeviceOverrideRepository deviceOverrideRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceOverride> getActiveOverride(Classroom classroom, String deviceType) {
        if (classroom == null || deviceType == null) return Optional.empty();
        List<DeviceOverride> active = deviceOverrideRepository.findActiveOverrides(
                classroom, deviceType.toUpperCase(), LocalDateTime.now());
        return active.isEmpty() ? Optional.empty() : Optional.of(active.get(0));
    }

    @Override
    @Transactional
    public DeviceOverride setOverride(
            Classroom classroom,
            String deviceType,
            String forcedState,
            String setBy,
            String reason,
            int durationMinutes) {

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(durationMinutes > 0 ? durationMinutes : 60);

        // Deactivate any previous active overrides for this device type in this classroom
        List<DeviceOverride> existing = deviceOverrideRepository.findActiveOverrides(
                classroom, deviceType.toUpperCase(), now);
        for (DeviceOverride o : existing) {
            o.setActive(false);
            deviceOverrideRepository.save(o);
        }

        DeviceOverride override = DeviceOverride.builder()
                .classroom(classroom)
                .deviceType(deviceType.toUpperCase())
                .forcedState(forcedState.toUpperCase())
                .setBy(setBy != null ? setBy : "TEACHER")
                .reason(reason != null ? reason : "Manual teacher override")
                .setAt(now)
                .expiresAt(expiresAt)
                .active(true)
                .build();

        log.info("Set durable override: Classroom={}, Type={}, State={}, Expires={}",
                classroom.getRoomNumber(), deviceType, forcedState, expiresAt);

        return deviceOverrideRepository.save(override);
    }

    @Override
    @Transactional
    public void clearOverride(Long overrideId) {
        deviceOverrideRepository.findById(overrideId).ifPresent(o -> {
            o.setActive(false);
            deviceOverrideRepository.save(o);
            log.info("Cleared override id={}", overrideId);
        });
    }

    @Override
    @Transactional
    public void clearAllOverridesForClassroom(Classroom classroom) {
        List<DeviceOverride> active = deviceOverrideRepository.findByClassroomAndActiveTrue(classroom);
        for (DeviceOverride o : active) {
            o.setActive(false);
            deviceOverrideRepository.save(o);
        }
        log.info("Cleared all overrides for classroom={}", classroom.getRoomNumber());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceOverride> getActiveOverrides(Classroom classroom) {
        LocalDateTime now = LocalDateTime.now();
        return deviceOverrideRepository.findByClassroomAndActiveTrue(classroom)
                .stream()
                .filter(o -> !o.isExpired(now))
                .toList();
    }
}
