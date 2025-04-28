package org.sks.portsmanagement.wsconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TelemetryWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketErrorBroadcaster errorBroadcaster;
    private static ObjectMapper objectMapper = new ObjectMapper();
    private static final ConcurrentHashMap<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    public TelemetryWebSocketHandler(WebSocketErrorBroadcaster errorBroadcaster, ObjectMapper objectMapper) {
        this.errorBroadcaster = errorBroadcaster;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        activeSessions.put(session.getId(), session);
        errorBroadcaster.addSession(session);
        sendConnectionAck(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // Optional: Handle incoming commands if needed
        String payload = message.getPayload();
        session.sendMessage(new TextMessage(
                objectMapper.writeValueAsString(
                        Map.of("status", "acknowledged", "received", payload)
                )
        ));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        cleanupSession(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        cleanupSession(session);
        session.close(CloseStatus.SERVER_ERROR);
    }

    public static void sendTelemetryData(Map<String, Object> telemetryData) {
        broadcastData(telemetryData);
    }

    public void sendMissionData(List<Map<String, Object>> missionDataList) {
        broadcastData(missionDataList);
    }

    private static void broadcastData(Object data) {
        try {
            String jsonData = objectMapper.writeValueAsString(data);
            TextMessage message = new TextMessage(jsonData);

            activeSessions.forEach((id, session) -> {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(message);
                    } catch (IOException e) {
                        System.err.println("Error sending message to session " + id + ": " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            System.err.println("Error serializing data: " + e.getMessage());
        }
    }

    private void sendConnectionAck(WebSocketSession session) throws IOException {
        session.sendMessage(new TextMessage(
                objectMapper.writeValueAsString(
                        Map.of(
                                "status", "connected",
                                "message", "Telemetry WebSocket connection established",
                                "timestamp", System.currentTimeMillis()
                        )
                )
        ));
    }

    private void cleanupSession(WebSocketSession session) {
        activeSessions.remove(session.getId());
        errorBroadcaster.removeSession(session);
    }
}
