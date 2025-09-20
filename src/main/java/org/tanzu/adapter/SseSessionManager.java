package org.tanzu.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Server-Sent Events (SSE) sessions for MCP protocol adapter.
 * Provides session lifecycle management including creation, removal, and message broadcasting
 * to connected SSE clients using reactive streams.
 */
@Component
public class SseSessionManager {
    
    private static final Logger logger = LoggerFactory.getLogger(SseSessionManager.class);
    
    private final Map<String, Sinks.Many<String>> sessions = new ConcurrentHashMap<>();
    
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        sessions.put(sessionId, sink);
        logger.info("Created SSE session: {}", sessionId);
        return sessionId;
    }
    
    public void removeSession(String sessionId) {
        Sinks.Many<String> sink = sessions.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
            logger.info("Removed SSE session: {}", sessionId);
        }
    }
    
    public void sendToAllSessions(String message) {
        sessions.values().forEach(sink -> sink.tryEmitNext(message));
    }
    
    public Flux<String> getSessionFlux(String sessionId) {
        Sinks.Many<String> sink = sessions.get(sessionId);
        return sink != null ? sink.asFlux() : Flux.empty();
    }
    
    public int getActiveSessionCount() {
        return sessions.size();
    }
}