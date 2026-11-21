package com.finalYear.smartClassRoom.config;

import com.finalYear.smartClassRoom.websocket.FaceAgentWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class FaceAgentWebSocketConfig implements WebSocketConfigurer {

    private final FaceAgentWebSocketHandler faceAgentWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(faceAgentWebSocketHandler, "/ws/agent")
                .setAllowedOriginPatterns("*");
    }
}
