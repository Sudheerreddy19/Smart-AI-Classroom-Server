package com.finalYear.smartClassRoom.service.impl;

import com.finalYear.smartClassRoom.dto.request.DeviceRequest;
import com.finalYear.smartClassRoom.dto.response.DeviceResponse;
import com.finalYear.smartClassRoom.entity.Classroom;
import com.finalYear.smartClassRoom.entity.Device;
import com.finalYear.smartClassRoom.entity.DeviceCommand;
import com.finalYear.smartClassRoom.exception.DuplicateResourceException;
import com.finalYear.smartClassRoom.exception.ResourceNotFoundException;
import com.finalYear.smartClassRoom.repository.ClassroomRepository;
import com.finalYear.smartClassRoom.repository.DeviceCommandRepository;
import com.finalYear.smartClassRoom.repository.DeviceRepository;
import com.finalYear.smartClassRoom.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceServiceImpl implements DeviceService {

    private final DeviceRepository deviceRepository;
    private final ClassroomRepository classroomRepository;
    private final DeviceCommandRepository deviceCommandRepository;

    @Override
    @Transactional
    public DeviceResponse registerDevice(DeviceRequest request) {

        if (deviceRepository.existsByDeviceId(request.getDeviceId())) {
            throw new DuplicateResourceException(
                    "Device already exists: " + request.getDeviceId());
        }

        Classroom classroom = null;

        if (request.getClassroomId() != null) {
            classroom = classroomRepository.findById(request.getClassroomId())
                    .orElseThrow(() ->
                            new ResourceNotFoundException(
                                    "Classroom", request.getClassroomId()));
        }

        Device device = Device.builder()
                .deviceId(request.getDeviceId())
                .deviceName(request.getDeviceName())
                .deviceType(request.getDeviceType())
                .classroom(classroom)
                .ipAddress(request.getIpAddress())
                .macAddress(request.getMacAddress())
                .firmwareVersion(request.getFirmwareVersion())
                .status(Device.DeviceStatus.OFFLINE)
                .active(true)
                .lastSeen(LocalDateTime.now())
                .build();

        return toResponse(deviceRepository.save(device));
    }

    @Override
    @Transactional
    public DeviceResponse updateDevice(Long id, DeviceRequest request) {

        Device device = deviceRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Device", id));

        device.setDeviceName(request.getDeviceName());
        device.setDeviceType(request.getDeviceType());
        device.setIpAddress(request.getIpAddress());
        device.setMacAddress(request.getMacAddress());
        device.setFirmwareVersion(request.getFirmwareVersion());

        if (request.getClassroomId() != null) {
            Classroom classroom = classroomRepository.findById(request.getClassroomId())
                    .orElseThrow(() ->
                            new ResourceNotFoundException(
                                    "Classroom", request.getClassroomId()));

            device.setClassroom(classroom);
        }

        return toResponse(deviceRepository.save(device));
    }

    @Override
    public DeviceResponse getById(Long id) {

        return toResponse(
                deviceRepository.findById(id)
                        .orElseThrow(() ->
                                new ResourceNotFoundException("Device", id))
        );
    }

    @Override
    public List<DeviceResponse> getAll() {

        return deviceRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public List<DeviceResponse> getByClassroom(Long classroomId) {

        Classroom classroom = classroomRepository.findById(classroomId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Classroom", classroomId));

        return deviceRepository.findByClassroom(classroom)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void controlDevice(String deviceId, String action) {

        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Device ID " + deviceId));

        String upperAction = action.toUpperCase(Locale.ROOT);
        if ("ON".equals(upperAction)) {
            device.setStatus(Device.DeviceStatus.ONLINE);
        } else if ("OFF".equals(upperAction)) {
            device.setStatus(Device.DeviceStatus.OFFLINE);
        } else {
            log.warn("Unknown action: {}", action);
        }

        deviceRepository.save(device);

        // Command lifecycle tracking
        if (device.getClassroom() != null) {
            LocalDateTime now = LocalDateTime.now();
            DeviceCommand cmd = DeviceCommand.builder()
                    .commandId(UUID.randomUUID().toString())
                    .classroom(device.getClassroom())
                    .targetType(device.getDeviceType() != null ? device.getDeviceType().name() : "DEVICE")
                    .targetDeviceId(deviceId)
                    .action(upperAction)
                    .issuedBy("DIRECT_DEVICE_API")
                    .reason("Direct control via /api/devices/{id}/control")
                    .status(DeviceCommand.CommandStatus.ACKNOWLEDGED)
                    .issuedAt(now)
                    .dispatchedAt(now)
                    .acknowledgedAt(now)
                    .build();
            deviceCommandRepository.save(cmd);
        }

        log.info("Device {} action {}", deviceId, action);
    }

    @Override
    @Transactional
    public void updateHeartbeat(String deviceId) {

        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Device ID " + deviceId));

        device.setLastSeen(LocalDateTime.now());
        device.setStatus(Device.DeviceStatus.ONLINE);

        deviceRepository.save(device);
    }

    @Override
    @Transactional
    public void delete(Long id) {

        Device device = deviceRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Device", id));

        device.setActive(false);

        deviceRepository.save(device);
    }

    private DeviceResponse toResponse(Device device) {

        return DeviceResponse.builder()
                .id(device.getId())
                .deviceId(device.getDeviceId())
                .deviceName(device.getDeviceName())
                .deviceType(device.getDeviceType())
                .classroomId(device.getClassroom() != null
                        ? device.getClassroom().getId()
                        : null)
                .roomNumber(device.getClassroom() != null
                        ? device.getClassroom().getRoomNumber()
                        : null)
                .status(device.getStatus())
                .ipAddress(device.getIpAddress())
                .macAddress(device.getMacAddress())
                .firmwareVersion(device.getFirmwareVersion())
                .lastSeen(device.getLastSeen())
                .active(device.isActive())
                .build();
    }
}
