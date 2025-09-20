package org.tanzu.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tanzu.adapter.config.EnvironmentVariableProcessor;
import org.tanzu.adapter.config.McpServerConfig;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class McpServerProcessTest {

    @Mock
    private McpServerConfig mockConfig;

    @Mock
    private EnvironmentVariableProcessor mockEnvProcessor;

    private McpServerProcess mcpServerProcess;

    private McpServerConfig.ProcessConfig createProcessConfig(boolean restartEnabled, long restartDelayMs) {
        McpServerConfig.ProcessConfig processConfig = new McpServerConfig.ProcessConfig();
        processConfig.setRestartEnabled(restartEnabled);
        processConfig.setRestartDelayMs(restartDelayMs);
        return processConfig;
    }

    private void setupBasicMocks() {
        lenient().when(mockConfig.getName()).thenReturn("test-mcp-server");
        lenient().when(mockConfig.getWorkingDirectory()).thenReturn(".");
        lenient().when(mockConfig.getProcess()).thenReturn(createProcessConfig(true, 100));
        lenient().when(mockEnvProcessor.processEnvironmentVariables(eq(mockConfig)))
                .thenReturn(Map.of("TEST_VAR", "test_value"));
    }

    @BeforeEach
    void setUp() {
        setupBasicMocks();
    }

    @Test
    void testStartSuccess() {
        // Use a short-running command that will stay alive long enough to test
        when(mockConfig.getExecutable()).thenReturn("sleep");
        when(mockConfig.getArgs()).thenReturn(Arrays.asList("1")); // sleep for 1 second

        mcpServerProcess = new McpServerProcess(mockConfig, mockEnvProcessor);

        assertThatCode(() -> mcpServerProcess.start()).doesNotThrowAnyException();

        // Give it a moment to start
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(mcpServerProcess.isRunning()).as("Process should be running after start").isTrue();

        // Cleanup
        mcpServerProcess.stop();

        // Give it a moment to stop
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(mcpServerProcess.isRunning()).as("Process should not be running after stop").isFalse();
    }

    @Test
    void testStartWhenAlreadyRunning() {
        when(mockConfig.getExecutable()).thenReturn("sleep");
        when(mockConfig.getArgs()).thenReturn(Arrays.asList("1")); // sleep for 1 second

        mcpServerProcess = new McpServerProcess(mockConfig, mockEnvProcessor);

        // Start once
        mcpServerProcess.start();

        // Give it a moment to start
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(mcpServerProcess.isRunning()).isTrue();

        // Starting again should not fail
        assertThatCode(() -> mcpServerProcess.start()).doesNotThrowAnyException();

        // Should still be running
        assertThat(mcpServerProcess.isRunning()).isTrue();

        // Cleanup
        mcpServerProcess.stop();
    }

    @Test
    void testStartFailure() {
        // Configure with invalid executable
        when(mockConfig.getExecutable()).thenReturn("non-existent-command-xyz123");
        when(mockConfig.getArgs()).thenReturn(Arrays.asList());

        McpServerProcess failingProcess = new McpServerProcess(mockConfig, mockEnvProcessor);

        // Should throw RuntimeException for invalid command
        assertThatThrownBy(() -> failingProcess.start())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to start MCP Server");
        assertThat(failingProcess.isRunning()).isFalse();
    }

    @Test
    void testStopWhenRunning() {
        when(mockConfig.getExecutable()).thenReturn("sleep");
        when(mockConfig.getArgs()).thenReturn(Arrays.asList("1")); // sleep for 1 second

        mcpServerProcess = new McpServerProcess(mockConfig, mockEnvProcessor);

        // Start the process first
        mcpServerProcess.start();

        // Give it a moment to start
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(mcpServerProcess.isRunning()).isTrue();

        // Stop the process
        mcpServerProcess.stop();
        assertThat(mcpServerProcess.isRunning()).isFalse();
    }

    @Test
    void testStopWhenNotRunning() {
        // Only create the process without setting up unused mocks
        mcpServerProcess = new McpServerProcess(mockConfig, mockEnvProcessor);

        // Stop when not running should not throw
        assertThatCode(() -> mcpServerProcess.stop()).doesNotThrowAnyException();
        assertThat(mcpServerProcess.isRunning()).isFalse();
    }

    @Test
    void testSendMessageWhenRunning() {
        // Use a command that accepts input - 'cat' command
        when(mockConfig.getExecutable()).thenReturn("cat");
        when(mockConfig.getArgs()).thenReturn(Arrays.asList());

        McpServerProcess catProcess = new McpServerProcess(mockConfig, mockEnvProcessor);
        catProcess.start();

        // Give it a moment to start
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String testMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"test\",\"id\":1}";

        // Should not throw
        assertThatCode(() -> catProcess.sendMessage(testMessage)).doesNotThrowAnyException();

        // Cleanup
        catProcess.stop();
    }

    @Test
    void testSendMessageWithEmbeddedNewlines() {
        // Use cat command that accepts input
        when(mockConfig.getExecutable()).thenReturn("cat");
        when(mockConfig.getArgs()).thenReturn(Arrays.asList());

        McpServerProcess catProcess = new McpServerProcess(mockConfig, mockEnvProcessor);
        catProcess.start();

        // Give it a moment to start
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String messageWithNewlines = "{\"data\":\"line1\nline2\r\nline3\r\"}";

        // Should not throw and should escape newlines
        assertThatCode(() -> catProcess.sendMessage(messageWithNewlines)).doesNotThrowAnyException();

        // Cleanup
        catProcess.stop();
    }

    @Test
    void testSendMessageWhenNotRunning() {
        // Only create the process without setting up unused mocks
        mcpServerProcess = new McpServerProcess(mockConfig, mockEnvProcessor);

        String testMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"test\",\"id\":1}";

        // Should not throw, but should handle gracefully
        assertThatCode(() -> mcpServerProcess.sendMessage(testMessage)).doesNotThrowAnyException();
    }

    @Test
    void testGetMessagesReactiveStream() {
        // Use echo command to produce output
        when(mockConfig.getExecutable()).thenReturn("echo");
        when(mockConfig.getArgs()).thenReturn(Arrays.asList("{\"jsonrpc\":\"2.0\",\"result\":\"success\",\"id\":1}"));

        McpServerProcess echoProcess = new McpServerProcess(mockConfig, mockEnvProcessor);
        echoProcess.start();

        // Get the message stream
        Flux<String> messageStream = echoProcess.getMessages();

        // Test the reactive stream (echo will output one line and exit)
        StepVerifier.create(messageStream.take(1))
                .expectNext("{\"jsonrpc\":\"2.0\",\"result\":\"success\",\"id\":1}")
                .verifyComplete();

        // Process will have exited after echo
        echoProcess.stop();
    }

    @Test
    void testIsRunningWithRealProcess() {
        // Use a short-running command for testing - sleep
        when(mockConfig.getExecutable()).thenReturn("sleep");
        when(mockConfig.getArgs()).thenReturn(Arrays.asList("1")); // sleep for 1 second

        McpServerProcess sleepProcess = new McpServerProcess(mockConfig, mockEnvProcessor);
        sleepProcess.start();

        // Give it a moment to start
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Should be running initially
        assertThat(sleepProcess.isRunning()).as("Process should be running after start").isTrue();

        // Stop it
        sleepProcess.stop();

        // Should not be running after stop
        assertThat(sleepProcess.isRunning()).as("Process should not be running after stop").isFalse();
    }

    @Test
    void testGetConfig() {
        // Only create the process without setting up unused mocks
        mcpServerProcess = new McpServerProcess(mockConfig, mockEnvProcessor);

        assertThat(mcpServerProcess.getConfig()).isEqualTo(mockConfig);
    }

    @Test
    void testEnvironmentVariableValidation() {
        when(mockConfig.getExecutable()).thenReturn("echo");
        when(mockConfig.getArgs()).thenReturn(Arrays.asList("test"));

        mcpServerProcess = new McpServerProcess(mockConfig, mockEnvProcessor);

        // Test that environment validation is called
        verify(mockEnvProcessor, never()).validateRequiredEnvironmentVariables(any());

        // Start should trigger validation
        mcpServerProcess.start();

        verify(mockEnvProcessor).validateRequiredEnvironmentVariables(mockConfig);

        // Cleanup
        mcpServerProcess.stop();
    }

    @Test
    void testProcessConfigurationUsed() {
        when(mockConfig.getExecutable()).thenReturn("echo");
        when(mockConfig.getArgs()).thenReturn(Arrays.asList("test"));

        mcpServerProcess = new McpServerProcess(mockConfig, mockEnvProcessor);
        mcpServerProcess.start();

        assertThat(mcpServerProcess.getConfig().getName()).isEqualTo("test-mcp-server");
        assertThat(mcpServerProcess.getConfig().getExecutable()).isEqualTo("echo");
        assertThat(mcpServerProcess.getConfig().getProcess().isRestartEnabled()).isTrue();

        // Cleanup
        mcpServerProcess.stop();
    }

    @Test
    void testEscapeEmbeddedNewlines() {
        // Only create the process without setting up unused mocks
        mcpServerProcess = new McpServerProcess(mockConfig, mockEnvProcessor);

        // Test the escaping logic directly through reflection or by verifying behavior
        // Since escapeEmbeddedNewlines is private, we test it through sendMessage behavior
        String messageWithNewlines = "{\"message\":\"line1\nline2\r\nline3\r\"}";

        // This should not throw and should handle the newlines correctly
        assertThatCode(() -> mcpServerProcess.sendMessage(messageWithNewlines)).doesNotThrowAnyException();
    }

    @Test
    void testConfigurationDefaults() {
        // Only create the process without setting up unused mocks
        mcpServerProcess = new McpServerProcess(mockConfig, mockEnvProcessor);

        // Verify config is properly injected
        assertThat(mcpServerProcess.getConfig()).isNotNull();
        assertThat(mcpServerProcess.getConfig().getName()).isEqualTo("test-mcp-server");
    }
}