package com.finalYear.smartClassRoom.service;

import com.finalYear.smartClassRoom.entity.Classroom;
import com.finalYear.smartClassRoom.entity.DeviceOverride;

import java.util.List;
import java.util.Optional;

public interface DeviceOverrideService {

    Optional<DeviceOverride> getActiveOverride(Classroom classroom, String deviceType);

    DeviceOverride setOverride(Classroom classroom, String deviceType, String forcedState, String setBy, String reason, int durationMinutes);

    void clearOverride(Long overrideId);

    void clearAllOverridesForClassroom(Classroom classroom);

    List<DeviceOverride> getActiveOverrides(Classroom classroom);
}
