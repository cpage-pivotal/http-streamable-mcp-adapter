package org.tanzu.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages request contexts for streamable HTTP MCP protocol adapter.
 * Replaces SSE session management with lightweight request tracking,
 * correlation IDs, and timeout management for stateless HTTP requests.
 */
@Component
public class RequestContextManager {

    private static final Logger logger = LoggerFactory.getLogger(RequestContextManager.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Request contexts mapped by correlation ID
    private final Map<String, RequestContext> activeRequests = new ConcurrentHashMap<>();

    // Default timeout for requests (30 seconds as per design)
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Creates a new request context for the given JSON-RPC message.
     * Extracts or generates a correlation ID for tracking responses.
     *
     * @param jsonRpcMessage The JSON-RPC message
     * @return The correlation ID for this request
     */
    public String createRequestContext(String jsonRpcMessage) {
        String correlationId = extractOrGenerateCorrelationId(jsonRpcMessage);

        RequestContext context = new RequestContext(
                correlationId,
                jsonRpcMessage,
                Instant.now(),
                Sinks.many().multicast().onBackpressureBuffer()
        );

        activeRequests.put(correlationId, context);
        logger.info("Created request context: {}", correlationId);

        // Schedule cleanup after timeout
        scheduleTimeout(correlationId);

        return correlationId;
    }

    /**
     * Gets the response stream for a specific request correlation ID.
     *
     * @param correlationId The correlation ID
     * @return Flux of response messages for this request
     */
    public Flux<String> getResponseStream(String correlationId) {
        RequestContext context = activeRequests.get(correlationId);
        if (context == null) {
            logger.warn("No request context found for correlation ID: {}", correlationId);
            return Flux.empty();
        }

        return context.responseSink.asFlux()
                .doOnSubscribe(sub -> logger.debug("Subscribed to response stream for: {}", correlationId))
                .doOnTerminate(() -> logger.debug("Response stream terminated for: {}", correlationId));
    }

    /**
     * Sends a response message to the appropriate request context.
     * Matches responses to requests using JSON-RPC ID correlation.
     *
     * @param responseMessage The response message from MCP server
     */
    public void sendResponse(String responseMessage) {
        try {
            String correlationId = extractCorrelationId(responseMessage);

            if (correlationId == null) {
                // Handle notifications (no ID) - broadcast to all active requests
                logger.debug("Broadcasting notification to all active requests");
                activeRequests.values().forEach(context ->
                    context.responseSink.tryEmitNext(responseMessage));
                return;
            }

            RequestContext context = activeRequests.get(correlationId);
            if (context != null) {
                context.responseSink.tryEmitNext(responseMessage);
                logger.debug("Sent response to correlation ID {}: {}", correlationId, responseMessage);

                // Check if this is a final response (result or error)
                if (isFinalResponse(responseMessage)) {
                    completeRequest(correlationId);
                }
            } else {
                logger.warn("Received response for unknown correlation ID: {}", correlationId);
            }
        } catch (Exception e) {
            logger.error("Error processing response message: {}", responseMessage, e);
        }
    }

    /**
     * Completes and removes a request context.
     *
     * @param correlationId The correlation ID to complete
     */
    public void completeRequest(String correlationId) {
        RequestContext context = activeRequests.remove(correlationId);
        if (context != null) {
            context.responseSink.tryEmitComplete();
            logger.info("Completed request context: {}", correlationId);
        }
    }

    /**
     * Removes a request context due to timeout or error.
     *
     * @param correlationId The correlation ID to remove
     */
    public void removeRequest(String correlationId) {
        RequestContext context = activeRequests.remove(correlationId);
        if (context != null) {
            context.responseSink.tryEmitError(new RuntimeException("Request timeout or error"));
            logger.info("Removed request context: {}", correlationId);
        }
    }

    /**
     * Gets the number of active requests.
     *
     * @return Number of active request contexts
     */
    public int getActiveRequestCount() {
        return activeRequests.size();
    }

    /**
     * Extracts or generates a correlation ID from the JSON-RPC message.
     * Uses the JSON-RPC 'id' field if present, otherwise generates a UUID.
     *
     * @param jsonRpcMessage The JSON-RPC message
     * @return The correlation ID
     */
    private String extractOrGenerateCorrelationId(String jsonRpcMessage) {
        try {
            JsonNode messageNode = objectMapper.readTree(jsonRpcMessage);
            JsonNode idNode = messageNode.get("id");

            if (idNode != null && !idNode.isNull()) {
                return idNode.asText();
            }
        } catch (Exception e) {
            logger.warn("Failed to extract ID from JSON-RPC message: {}", jsonRpcMessage, e);
        }

        // Generate UUID for notifications or invalid messages
        String generatedId = UUID.randomUUID().toString();
        logger.debug("Generated correlation ID: {}", generatedId);
        return generatedId;
    }

    /**
     * Extracts correlation ID from a response message.
     *
     * @param responseMessage The response message
     * @return The correlation ID or null for notifications
     */
    private String extractCorrelationId(String responseMessage) {
        try {
            JsonNode messageNode = objectMapper.readTree(responseMessage);
            JsonNode idNode = messageNode.get("id");

            if (idNode != null && !idNode.isNull()) {
                return idNode.asText();
            }
        } catch (Exception e) {
            logger.warn("Failed to extract correlation ID from response: {}", responseMessage, e);
        }
        return null;
    }

    /**
     * Determines if a response message is final (contains result or error).
     *
     * @param responseMessage The response message
     * @return true if this is a final response
     */
    private boolean isFinalResponse(String responseMessage) {
        try {
            JsonNode messageNode = objectMapper.readTree(responseMessage);
            return messageNode.has("result") || messageNode.has("error");
        } catch (Exception e) {
            logger.warn("Failed to determine if response is final: {}", responseMessage, e);
            return false;
        }
    }

    /**
     * Schedules cleanup of a request context after timeout.
     *
     * @param correlationId The correlation ID to schedule for cleanup
     */
    private void scheduleTimeout(String correlationId) {
        reactor.core.publisher.Mono.delay(DEFAULT_TIMEOUT)
                .subscribe(tick -> {
                    if (activeRequests.containsKey(correlationId)) {
                        logger.warn("Request timeout for correlation ID: {}", correlationId);
                        removeRequest(correlationId);
                    }
                });
    }

    /**
     * Internal class representing a request context.
     */
    private static class RequestContext {
        final String correlationId;
        final String originalMessage;
        final Instant createdAt;
        final Sinks.Many<String> responseSink;

        RequestContext(String correlationId, String originalMessage, Instant createdAt, Sinks.Many<String> responseSink) {
            this.correlationId = correlationId;
            this.originalMessage = originalMessage;
            this.createdAt = createdAt;
            this.responseSink = responseSink;
        }
    }

    /**
     * Cleanup method to remove expired request contexts.
     * Should be called periodically to prevent memory leaks.
     */
    public void cleanupExpiredRequests() {
        Instant cutoff = Instant.now().minus(DEFAULT_TIMEOUT);
        activeRequests.entrySet().removeIf(entry -> {
            if (entry.getValue().createdAt.isBefore(cutoff)) {
                entry.getValue().responseSink.tryEmitError(new RuntimeException("Request expired"));
                logger.info("Cleaned up expired request context: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }
}