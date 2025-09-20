package org.tanzu.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageBridgeTest {

    @Mock
    private McpServerProcess mockMcpServerProcess;

    @Mock
    private SseSessionManager mockSseSessionManager;

    private MessageBridge messageBridge;

    @BeforeEach
    void setUp() {
        messageBridge = new MessageBridge();

        // Use reflection to inject mocked dependencies
        try {
            var mcpServerField = MessageBridge.class.getDeclaredField("mcpServerProcess");
            mcpServerField.setAccessible(true);
            mcpServerField.set(messageBridge, mockMcpServerProcess);

            var sseSessionField = MessageBridge.class.getDeclaredField("sseSessionManager");
            sseSessionField.setAccessible(true);
            sseSessionField.set(messageBridge, mockSseSessionManager);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mocks", e);
        }
    }

    // Tests for bridgeClientToServer()
    @Test
    void testBridgeClientToServerWithValidRequest() {
        String validRequest = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{},\"id\":1}";

        Mono<Void> result = messageBridge.bridgeClientToServer(validRequest);

        StepVerifier.create(result)
                .verifyComplete();

        verify(mockMcpServerProcess).sendMessage(validRequest);
    }

    @Test
    void testBridgeClientToServerWithValidNotification() {
        String validNotification = "{\"jsonrpc\":\"2.0\",\"method\":\"notification\",\"params\":{}}";

        Mono<Void> result = messageBridge.bridgeClientToServer(validNotification);

        StepVerifier.create(result)
                .verifyComplete();

        verify(mockMcpServerProcess).sendMessage(validNotification);
    }

    @Test
    void testBridgeClientToServerWithValidResponse() {
        String validResponse = "{\"jsonrpc\":\"2.0\",\"result\":{\"success\":true},\"id\":1}";

        Mono<Void> result = messageBridge.bridgeClientToServer(validResponse);

        StepVerifier.create(result)
                .verifyComplete();

        verify(mockMcpServerProcess).sendMessage(validResponse);
    }

    @Test
    void testBridgeClientToServerWithInvalidJson() {
        String invalidJson = "{invalid json}";

        Mono<Void> result = messageBridge.bridgeClientToServer(invalidJson);

        StepVerifier.create(result)
                .expectError(RuntimeException.class)
                .verify();

        verify(mockMcpServerProcess, never()).sendMessage(any());
    }

    @Test
    void testBridgeClientToServerWithMissingJsonRpc() {
        String missingJsonRpc = "{\"method\":\"test\",\"id\":1}";

        Mono<Void> result = messageBridge.bridgeClientToServer(missingJsonRpc);

        StepVerifier.create(result)
                .expectError(RuntimeException.class)
                .verify();

        verify(mockMcpServerProcess, never()).sendMessage(any());
    }

    @Test
    void testBridgeClientToServerWithNullMessage() {
        Mono<Void> result = messageBridge.bridgeClientToServer(null);

        StepVerifier.create(result)
                .expectError(RuntimeException.class)
                .verify();

        verify(mockMcpServerProcess, never()).sendMessage(any());
    }

    // Tests for bridgeServerToClient()
    @Test
    void testBridgeServerToClientSuccess() {
        String serverMessage1 = "{\"jsonrpc\":\"2.0\",\"result\":{\"data\":\"test\"},\"id\":1}";
        String serverMessage2 = "{\"jsonrpc\":\"2.0\",\"method\":\"notification\"}";

        when(mockMcpServerProcess.getMessages())
                .thenReturn(Flux.just(serverMessage1, serverMessage2));

        Flux<ServerSentEvent<String>> result = messageBridge.bridgeServerToClient();

        StepVerifier.create(result)
                .expectNextMatches(event ->
                        "message".equals(event.event()) && serverMessage1.equals(event.data()))
                .expectNextMatches(event ->
                        "message".equals(event.event()) && serverMessage2.equals(event.data()))
                .verifyComplete();

        verify(mockSseSessionManager).sendToAllSessions(serverMessage1);
        verify(mockSseSessionManager).sendToAllSessions(serverMessage2);
    }

    @Test
    void testBridgeServerToClientWithAllElementsSucceed() {
        // Test normal processing where all elements succeed
        String serverMessage1 = "{\"jsonrpc\":\"2.0\",\"result\":{\"data\":\"test1\"},\"id\":1}";
        String serverMessage2 = "{\"jsonrpc\":\"2.0\",\"result\":{\"data\":\"test2\"},\"id\":2}";
        String serverMessage3 = "{\"jsonrpc\":\"2.0\",\"method\":\"notification\"}";

        when(mockMcpServerProcess.getMessages())
                .thenReturn(Flux.just(serverMessage1, serverMessage2, serverMessage3));

        // All sendToAllSessions calls succeed
        doNothing().when(mockSseSessionManager).sendToAllSessions(any());

        Flux<ServerSentEvent<String>> result = messageBridge.bridgeServerToClient();

        // All messages should be converted to SSE events
        StepVerifier.create(result)
                .expectNextMatches(event ->
                        "message".equals(event.event()) && serverMessage1.equals(event.data()))
                .expectNextMatches(event ->
                        "message".equals(event.event()) && serverMessage2.equals(event.data()))
                .expectNextMatches(event ->
                        "message".equals(event.event()) && serverMessage3.equals(event.data()))
                .verifyComplete();

        verify(mockSseSessionManager).sendToAllSessions(serverMessage1);
        verify(mockSseSessionManager).sendToAllSessions(serverMessage2);
        verify(mockSseSessionManager).sendToAllSessions(serverMessage3);
    }

    @Test
    void testBridgeServerToClientWithSourceError() {
        // When the source flux (mcpServerProcess.getMessages()) errors immediately,
        // the resulting flux should also error because onErrorContinue only handles
        // element processing errors, not source flux errors
        when(mockMcpServerProcess.getMessages())
                .thenReturn(Flux.error(new RuntimeException("Source error")));

        Flux<ServerSentEvent<String>> result = messageBridge.bridgeServerToClient();

        StepVerifier.create(result)
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void testBridgeServerToClientWithElementProcessingError() {
        // Test how onErrorContinue handles errors in element processing
        // When sendToAllSessions throws an exception, onErrorContinue will:
        // 1. Log the error
        // 2. DROP the failing element from the stream
        // 3. Continue processing the next element

        String serverMessage1 = "{\"jsonrpc\":\"2.0\",\"result\":{\"data\":\"test\"},\"id\":1}";
        String serverMessage2 = "{\"jsonrpc\":\"2.0\",\"method\":\"notification\"}";

        when(mockMcpServerProcess.getMessages())
                .thenReturn(Flux.just(serverMessage1, serverMessage2));

        // Make sendToAllSessions throw an exception for the first message
        doThrow(new RuntimeException("Session error"))
                .when(mockSseSessionManager).sendToAllSessions(serverMessage1);

        // Normal processing for second message
        doNothing().when(mockSseSessionManager).sendToAllSessions(serverMessage2);

        Flux<ServerSentEvent<String>> result = messageBridge.bridgeServerToClient();

        // With onErrorContinue, when sendToAllSessions fails for serverMessage1:
        // - The error is logged
        // - serverMessage1 is DROPPED from the stream
        // - Only serverMessage2 continues to create an SSE event
        StepVerifier.create(result)
                .expectNextMatches(event ->
                        "message".equals(event.event()) && serverMessage2.equals(event.data()))
                .verifyComplete();

        // Both sendToAllSessions calls should have been attempted
        verify(mockSseSessionManager).sendToAllSessions(serverMessage1);
        verify(mockSseSessionManager).sendToAllSessions(serverMessage2);
    }

    @Test
    void testBridgeServerToClientEmptyStream() {
        when(mockMcpServerProcess.getMessages())
                .thenReturn(Flux.empty());

        Flux<ServerSentEvent<String>> result = messageBridge.bridgeServerToClient();

        StepVerifier.create(result)
                .verifyComplete();
    }

    // Tests for validateJsonRpcMessage()
    @Test
    void testValidateValidJsonRpcRequest() {
        String validRequest = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{},\"id\":1}";

        assertThatCode(() -> messageBridge.validateJsonRpcMessage(validRequest)).doesNotThrowAnyException();
    }

    @Test
    void testValidateValidJsonRpcNotification() {
        String validNotification = "{\"jsonrpc\":\"2.0\",\"method\":\"notification\",\"params\":{}}";

        assertThatCode(() -> messageBridge.validateJsonRpcMessage(validNotification)).doesNotThrowAnyException();
    }

    @Test
    void testValidateValidJsonRpcResponse() {
        String validResponse = "{\"jsonrpc\":\"2.0\",\"result\":{\"success\":true},\"id\":1}";

        assertThatCode(() -> messageBridge.validateJsonRpcMessage(validResponse)).doesNotThrowAnyException();
    }

    @Test
    void testValidateValidJsonRpcError() {
        String validError = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-1,\"message\":\"Error\"},\"id\":1}";

        assertThatCode(() -> messageBridge.validateJsonRpcMessage(validError)).doesNotThrowAnyException();
    }

    @Test
    void testValidateNullMessage() {
        assertThatThrownBy(() -> messageBridge.validateJsonRpcMessage(null))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void testValidateEmptyMessage() {
        assertThatThrownBy(() -> messageBridge.validateJsonRpcMessage(""))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("cannot be null or empty");
    }

    @Test
    void testValidateInvalidJson() {
        String invalidJson = "{invalid json}";

        assertThatThrownBy(() -> messageBridge.validateJsonRpcMessage(invalidJson))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Invalid JSON format");
    }

    @Test
    void testValidateMissingJsonRpcField() {
        String missingJsonRpc = "{\"method\":\"test\",\"id\":1}";

        assertThatThrownBy(() -> messageBridge.validateJsonRpcMessage(missingJsonRpc))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Missing required 'jsonrpc' field");
    }

    @Test
    void testValidateInvalidJsonRpcVersion() {
        String invalidVersion = "{\"jsonrpc\":\"1.0\",\"method\":\"test\",\"id\":1}";

        assertThatThrownBy(() -> messageBridge.validateJsonRpcMessage(invalidVersion))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Invalid JSON-RPC version");
    }

    @Test
    void testValidateMissingMethodAndResult() {
        String missingFields = "{\"jsonrpc\":\"2.0\",\"id\":1}";

        assertThatThrownBy(() -> messageBridge.validateJsonRpcMessage(missingFields))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("missing method, result, or error");
    }

    @Test
    void testValidateEmptyMethodName() {
        String emptyMethod = "{\"jsonrpc\":\"2.0\",\"method\":\"\",\"id\":1}";

        assertThatThrownBy(() -> messageBridge.validateJsonRpcMessage(emptyMethod))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Method name cannot be empty");
    }

    @Test
    void testValidateResponseMissingId() {
        String responseMissingId = "{\"jsonrpc\":\"2.0\",\"result\":{\"success\":true}}";

        assertThatThrownBy(() -> messageBridge.validateJsonRpcMessage(responseMissingId))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Response message must have an 'id' field");
    }

    @Test
    void testValidateResponseWithBothResultAndError() {
        String bothResultAndError = "{\"jsonrpc\":\"2.0\",\"result\":{},\"error\":{\"code\":-1,\"message\":\"Error\"},\"id\":1}";

        assertThatThrownBy(() -> messageBridge.validateJsonRpcMessage(bothResultAndError))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Response cannot have both result and error");
    }

    @Test
    void testValidateRequestWithResultError() {
        String requestWithResult = "{\"jsonrpc\":\"2.0\",\"method\":\"test\",\"result\":{},\"id\":1}";

        assertThatThrownBy(() -> messageBridge.validateJsonRpcMessage(requestWithResult))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Request cannot have both method and result/error");
    }

    // Tests for isHealthy()
    @Test
    void testIsHealthyWhenServerRunning() {
        when(mockMcpServerProcess.isRunning()).thenReturn(true);

        assertThat(messageBridge.isHealthy()).isTrue();
    }

    @Test
    void testIsHealthyWhenServerNotRunning() {
        when(mockMcpServerProcess.isRunning()).thenReturn(false);

        assertThat(messageBridge.isHealthy()).isFalse();
    }

    // Tests for getActiveSessionCount()
    @Test
    void testGetActiveSessionCount() {
        when(mockSseSessionManager.getActiveSessionCount()).thenReturn(5);

        assertThat(messageBridge.getActiveSessionCount()).isEqualTo(5);
    }

    // Tests for getHealthInfo()
    @Test
    void testGetHealthInfo() {
        when(mockMcpServerProcess.isRunning()).thenReturn(true);
        when(mockMcpServerProcess.getConfig()).thenReturn(mock(org.tanzu.adapter.config.McpServerConfig.class));
        when(mockMcpServerProcess.getConfig().getName()).thenReturn("test-server");
        when(mockSseSessionManager.getActiveSessionCount()).thenReturn(3);

        var healthInfo = messageBridge.getHealthInfo();

        assertThat(healthInfo.get("mcpServerRunning")).isEqualTo(true);
        assertThat(healthInfo.get("mcpServerName")).isEqualTo("test-server");
        assertThat(healthInfo.get("activeSessionCount")).isEqualTo(3);
        assertThat(healthInfo.get("bridgeStatus")).isEqualTo("healthy");
    }

    @Test
    void testGetHealthInfoWhenUnhealthy() {
        when(mockMcpServerProcess.isRunning()).thenReturn(false);
        when(mockMcpServerProcess.getConfig()).thenReturn(mock(org.tanzu.adapter.config.McpServerConfig.class));
        when(mockMcpServerProcess.getConfig().getName()).thenReturn("test-server");
        when(mockSseSessionManager.getActiveSessionCount()).thenReturn(0);

        var healthInfo = messageBridge.getHealthInfo();

        assertThat(healthInfo.get("mcpServerRunning")).isEqualTo(false);
        assertThat(healthInfo.get("mcpServerName")).isEqualTo("test-server");
        assertThat(healthInfo.get("activeSessionCount")).isEqualTo(0);
        assertThat(healthInfo.get("bridgeStatus")).isEqualTo("unhealthy");
    }

    // Additional edge case tests
    @Test
    void testValidateJsonRpcWithNullJsonRpcField() {
        String nullJsonRpc = "{\"jsonrpc\":null,\"method\":\"test\",\"id\":1}";

        assertThatThrownBy(() -> messageBridge.validateJsonRpcMessage(nullJsonRpc))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Invalid JSON-RPC version");
    }

    @Test
    void testValidateComplexValidMessage() {
        String complexMessage = """
            {
                "jsonrpc": "2.0",
                "method": "repos/list_repositories",
                "params": {
                    "visibility": "public",
                    "sort": "updated",
                    "per_page": 10,
                    "nested": {
                        "array": [1, 2, 3],
                        "object": {"key": "value"}
                    }
                },
                "id": "req-123"
            }
            """;

        assertThatCode(() -> messageBridge.validateJsonRpcMessage(complexMessage)).doesNotThrowAnyException();
    }

    @Test
    void testBridgeClientToServerWithComplexValidMessage() {
        String complexMessage = """
            {
                "jsonrpc": "2.0",
                "method": "repos/create_issue",
                "params": {
                    "repo": "test/repo",
                    "title": "Test Issue",
                    "body": "This is a test issue\\nwith newlines"
                },
                "id": 42
            }
            """;

        Mono<Void> result = messageBridge.bridgeClientToServer(complexMessage);

        StepVerifier.create(result)
                .verifyComplete();

        verify(mockMcpServerProcess).sendMessage(complexMessage);
    }

    // Test start/stop bridging functionality
    @Test
    void testStartBridging() {
        String serverMessage = "{\"jsonrpc\":\"2.0\",\"result\":{\"data\":\"test\"},\"id\":1}";

        when(mockMcpServerProcess.getMessages())
                .thenReturn(Flux.just(serverMessage));

        Flux<ServerSentEvent<String>> result = messageBridge.startBridging();

        StepVerifier.create(result)
                .expectNextMatches(event ->
                        "message".equals(event.event()) && serverMessage.equals(event.data()))
                .verifyComplete();

        verify(mockSseSessionManager).sendToAllSessions(serverMessage);
    }
}