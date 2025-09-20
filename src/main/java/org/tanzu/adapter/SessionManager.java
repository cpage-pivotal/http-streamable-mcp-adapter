package org.tanzu.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session manager for MCP Streamable HTTP SSE connections using WebFlux reactive streams.
 * Manages SSE session lifecycle, message routing, and cleanup.
 */
@Component
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    // Default session timeout (5 minutes)
    private static final long DEFAULT_TIMEOUT_MS = 5 * 60 * 1000;

    // Active SSE sessions
    private final Map<String, McpSession> activeSessions = new ConcurrentHashMap<>();

    /**
     * Creates a new SSE session.
     *
     * @return The new session ID
     */
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        McpSession session = new McpSession(sessionId, Instant.now());
        activeSessions.put(sessionId, session);

        logger.info("Created new SSE session: {}", sessionId);
        return sessionId;
    }

    /**
     * Creates a new SSE session with a specific session ID.
     *
     * @param sessionId The desired session ID
     * @return The session ID if created successfully
     * @throws IllegalArgumentException if the session ID already exists
     */
    public String createSession(String sessionId) {
        if (activeSessions.containsKey(sessionId)) {
            throw new IllegalArgumentException("Session ID already exists: " + sessionId);
        }

        McpSession session = new McpSession(sessionId, Instant.now());
        activeSessions.put(sessionId, session);

        logger.info("Created SSE session with provided ID: {}", sessionId);
        return sessionId;
    }

    /**
     * Registers a session for message streaming.
     *
     * @param sessionId The session ID
     * @return true if registered successfully
     */
    public boolean registerSession(String sessionId) {
        McpSession session = activeSessions.get(sessionId);
        if (session == null) {
            logger.warn("Cannot register non-existent session: {}", sessionId);
            return false;
        }

        logger.info("Registered session for streaming: {}", sessionId);
        return true;
    }

    /**
     * Checks if a session ID is valid and active.
     *
     * @param sessionId The session ID to validate
     * @return true if the session exists and is active
     */
    public boolean isValidSession(String sessionId) {
        if (sessionId == null) {
            return false;
        }

        McpSession session = activeSessions.get(sessionId);
        return session != null && !session.isExpired();
    }

    /**
     * Gets a message stream for a specific session.
     *
     * @param sessionId The session ID
     * @return Flux of messages for this session
     */
    public Flux<String> getMessageStream(String sessionId) {
        McpSession session = activeSessions.get(sessionId);
        if (session == null) {
            logger.warn("Cannot get message stream for non-existent session: {}", sessionId);
            return Flux.empty();
        }

        return session.getMessageSink().asFlux()
                .doOnNext(message -> {
                    session.updateLastActivity();
                    logger.debug("Streaming message to session {}: {}", sessionId, message);
                });
    }

    /**
     * Sends a message to a specific session.
     *
     * @param sessionId The session ID
     * @param message The message to send
     * @return true if sent successfully
     */
    public boolean sendMessage(String sessionId, String message) {
        McpSession session = activeSessions.get(sessionId);
        if (session == null) {
            logger.warn("Cannot send message to session {}: session not found", sessionId);
            return false;
        }

        try {
            Sinks.EmitResult result = session.getMessageSink().tryEmitNext(message);
            if (result.isSuccess()) {
                session.updateLastActivity();
                logger.debug("Sent message to session {}: {}", sessionId, message);
                return true;
            } else {
                logger.warn("Failed to send message to session {}: {}", sessionId, result);
                return false;
            }
        } catch (Exception e) {
            logger.error("Error sending message to session {}", sessionId, e);
            return false;
        }
    }

    /**
     * Broadcasts a message to all active sessions.
     *
     * @param message The message to broadcast
     * @return Number of sessions the message was sent to
     */
    public int broadcastMessage(String message) {
        int sentCount = 0;
        for (String sessionId : activeSessions.keySet()) {
            if (sendMessage(sessionId, message)) {
                sentCount++;
            }
        }
        logger.debug("Broadcast message to {} sessions", sentCount);
        return sentCount;
    }

    /**
     * Terminates a session and cleans up resources.
     *
     * @param sessionId The session ID to terminate
     */
    public void terminateSession(String sessionId) {
        McpSession session = activeSessions.remove(sessionId);
        if (session != null) {
            try {
                session.getMessageSink().tryEmitComplete();
            } catch (Exception e) {
                logger.debug("Error completing message sink for session {}", sessionId, e);
            }
            logger.info("Terminated session: {}", sessionId);
        }
    }

    /**
     * Gets the session for a given ID.
     *
     * @param sessionId The session ID
     * @return The session or null if not found
     */
    public McpSession getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }

    /**
     * Gets the number of active sessions.
     *
     * @return Number of active sessions
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /**
     * Cleans up expired sessions.
     *
     * @return Number of sessions cleaned up
     */
    public int cleanupExpiredSessions() {
        int cleanedUp = 0;
        for (Map.Entry<String, McpSession> entry : activeSessions.entrySet()) {
            if (entry.getValue().isExpired()) {
                terminateSession(entry.getKey());
                cleanedUp++;
            }
        }
        if (cleanedUp > 0) {
            logger.info("Cleaned up {} expired sessions", cleanedUp);
        }
        return cleanedUp;
    }

    /**
     * Represents an MCP SSE session using reactive streams.
     */
    public static class McpSession {
        private final String id;
        private final Instant createdAt;
        private Instant lastActivity;
        private String lastEventId;
        private final long timeoutMs;
        private final Sinks.Many<String> messageSink;

        public McpSession(String id, Instant createdAt) {
            this(id, createdAt, DEFAULT_TIMEOUT_MS);
        }

        public McpSession(String id, Instant createdAt, long timeoutMs) {
            this.id = id;
            this.createdAt = createdAt;
            this.lastActivity = createdAt;
            this.timeoutMs = timeoutMs;
            this.messageSink = Sinks.many().multicast().onBackpressureBuffer();
        }

        public String getId() {
            return id;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public Instant getLastActivity() {
            return lastActivity;
        }

        public void updateLastActivity() {
            this.lastActivity = Instant.now();
        }

        public String getLastEventId() {
            return lastEventId;
        }

        public void setLastEventId(String lastEventId) {
            this.lastEventId = lastEventId;
        }

        public Sinks.Many<String> getMessageSink() {
            return messageSink;
        }

        public boolean isExpired() {
            return Instant.now().isAfter(lastActivity.plusMillis(timeoutMs));
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }
    }
}