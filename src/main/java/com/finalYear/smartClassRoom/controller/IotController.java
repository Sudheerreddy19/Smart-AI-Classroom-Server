package com.finalYear.smartClassRoom.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/iot")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class IotController {

    private final EnvironmentController environmentController;

    @PostMapping("/telemetry")
    public ResponseEntity<String> receiveTelemetry(@RequestBody EnvironmentController.EnvironmentIngestRequest req) {
        log.info("Received IoT Telemetry from ESP32: RoomState={}, Temp={}, Humidity={}, Fan={}, Light={}",
                req.roomState(), req.temperature(), req.humidity(), req.fanStatus(), req.lightStatus());
        return environmentController.ingest(req);
    }
}
