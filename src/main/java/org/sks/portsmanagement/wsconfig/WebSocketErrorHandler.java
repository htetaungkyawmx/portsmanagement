package org.sks.portsmanagement.wsconfig;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public class WebSocketErrorHandler extends TextWebSocketHandler {

    private final WebSocketErrorBroadcaster errorBroadcaster;

    public WebSocketErrorHandler(WebSocketErrorBroadcaster errorBroadcaster) {
        this.errorBroadcaster = errorBroadcaster;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        errorBroadcaster.addSession(session);
        session.sendMessage(new TextMessage("{\"status\": \"connected\"}"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) throws Exception {
        errorBroadcaster.removeSession(session);
    }
}
