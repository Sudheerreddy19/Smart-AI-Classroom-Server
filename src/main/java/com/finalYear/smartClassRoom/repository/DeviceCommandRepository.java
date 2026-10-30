package com.finalYear.smartClassRoom.repository;

import com.finalYear.smartClassRoom.entity.Classroom;
import com.finalYear.smartClassRoom.entity.DeviceCommand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, Long> {

    Optional<DeviceCommand> findByCommandId(String commandId);

    List<DeviceCommand> findByClassroomOrderByIssuedAtDesc(Classroom classroom);

    List<DeviceCommand> findByClassroomAndStatus(Classroom classroom, DeviceCommand.CommandStatus status);
}
