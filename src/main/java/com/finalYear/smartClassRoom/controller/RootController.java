package com.finalYear.smartClassRoom.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class RootController {

    @RequestMapping(value = "/", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Map<String, Object>> root() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "Smart AI Classroom Server",
                "docs", "/swagger-ui.html"
        ));
    }
}
