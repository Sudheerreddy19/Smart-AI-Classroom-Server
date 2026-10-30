package com.finalYear.smartClassRoom.repository;

import com.finalYear.smartClassRoom.entity.Classroom;
import com.finalYear.smartClassRoom.entity.DeviceOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceOverrideRepository extends JpaRepository<DeviceOverride, Long> {

    @Query("SELECT o FROM DeviceOverride o WHERE o.classroom = :classroom AND o.active = true AND o.expiresAt > :now AND (o.deviceType = :deviceType OR o.deviceType = 'ALL') ORDER BY o.setAt DESC")
    List<DeviceOverride> findActiveOverrides(
            @Param("classroom") Classroom classroom,
            @Param("deviceType") String deviceType,
            @Param("now") LocalDateTime now
    );

    List<DeviceOverride> findByClassroomAndActiveTrue(Classroom classroom);
}
