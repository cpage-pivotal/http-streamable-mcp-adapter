package org.tanzu.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tanzu.adapter.config.EnvironmentVariableProcessor;
import org.tanzu.adapter.config.McpServerConfig;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generic MCP STDIO Transport compliant MCP Server Process Manager.
 *
 * This implementation follows the MCP STDIO specification:
 * - Messages are newline-delimited JSON-RPC over stdin/stdout
 * - Embedded newlines in JSON messages MUST be escaped
 * - Uses UTF-8 encoding
 * - Synchronized writes to prevent message interleaving
 * - Line-based reading for proper message boundaries
 */
@Component
public class McpServerProcess {

    private static final Logger logger = LoggerFactory.getLogger(McpServerProcess.class);

    private final McpServerConfig config;
    private final EnvironmentVariableProcessor envProcessor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Process process;
    private OutputStream processOutputStream;
    private BufferedReader processReader;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final Sinks.Many<String> outputSink = Sinks.many().multicast().onBackpressureBuffer();

    // Object for synchronizing writes to prevent message interleaving
    private final Object writeLock = new Object();

    @Autowired
    public McpServerProcess(McpServerConfig config, EnvironmentVariableProcessor envProcessor) {
        this.config = config;
        this.envProcessor = envProcessor;
    }

    @PostConstruct
    public void init() {
        start();
    }

    @PreDestroy
    public void cleanup() {
        stop();
    }

    public void start() {
        if (isRunning.get()) {
            logger.warn("MCP Server '{}' process is already running", config.getName());
            return;
        }

        try {
            // Validate required environment variables first
            envProcessor.validateRequiredEnvironmentVariables(config);

            ProcessBuilder processBuilder = new ProcessBuilder();
            
            // Set up command and arguments
            processBuilder.command().add(config.getExecutable());
            if (config.getArgs() != null && !config.getArgs().isEmpty()) {
                processBuilder.command().addAll(config.getArgs());
            }

            // Set working directory
            if (config.getWorkingDirectory() != null && !config.getWorkingDirectory().trim().isEmpty()) {
                processBuilder.directory(new File(config.getWorkingDirectory()));
            }

            // Process environment variables
            Map<String, String> processEnv = envProcessor.processEnvironmentVariables(config);
            Map<String, String> env = processBuilder.environment();
            env.putAll(processEnv);

            // Don't redirect error stream - let stderr be separate
            processBuilder.redirectErrorStream(false);

            logger.info("Starting MCP Server '{}': {} {}", 
                       config.getName(), config.getExecutable(), 
                       config.getArgs() != null ? String.join(" ", config.getArgs()) : "");
            
            process = processBuilder.start();

            // Set up I/O streams with explicit UTF-8 encoding
            processOutputStream = process.getOutputStream();
            processReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            );

            isRunning.set(true);

            // Start reading output in background
            startOutputReader();

            // Start error stream reader
            startErrorReader();

            logger.info("MCP Server '{}' started successfully", config.getName());

        } catch (IOException e) {
            logger.error("Failed to start MCP Server '{}' process", config.getName(), e);
            isRunning.set(false);
            throw new RuntimeException("Failed to start MCP Server '" + config.getName() + "'", e);
        }
    }

    public void stop() {
        if (!isRunning.get()) {
            return;
        }

        logger.info("Stopping MCP Server '{}' process", config.getName());
        isRunning.set(false);

        try {
            if (processOutputStream != null) {
                processOutputStream.close();
            }
            if (processReader != null) {
                processReader.close();
            }
            if (process != null) {
                process.destroyForcibly();
                process.waitFor();
            }
        } catch (Exception e) {
            logger.error("Error stopping MCP Server '{}' process", config.getName(), e);
        } finally {
            outputSink.tryEmitComplete();
            logger.info("MCP Server '{}' process stopped", config.getName());
        }
    }

    /**
     * Sends a message to the MCP Server following MCP STDIO specification.
     *
     * Key compliance features:
     * - Escapes embedded newlines in JSON messages as per MCP spec
     * - Uses UTF-8 encoding
     * - Synchronizes writes to prevent message interleaving
     * - Appends newline delimiter and flushes immediately
     *
     * @param message JSON-RPC message to send
     */
    public void sendMessage(String message) {
        if (!isRunning.get() || processOutputStream == null) {
            logger.error("Cannot send message - MCP Server '{}' is not running", config.getName());
            return;
        }

        try {
            // Escape embedded newlines as per MCP STDIO specification
            // https://spec.modelcontextprotocol.io/specification/basic/transports/#stdio
            String escapedMessage = escapeEmbeddedNewlines(message);

            logger.debug("Sending message to MCP Server '{}': {}", config.getName(), escapedMessage);

            // Synchronized write to prevent message interleaving
            synchronized (writeLock) {
                processOutputStream.write(escapedMessage.getBytes(StandardCharsets.UTF_8));
                processOutputStream.write("\n".getBytes(StandardCharsets.UTF_8));
                processOutputStream.flush();
            }

        } catch (IOException e) {
            logger.error("Failed to send message to MCP Server '{}'", config.getName(), e);
            // Try to restart the process on I/O error if restart is enabled
            if (config.getProcess().isRestartEnabled()) {
                restart();
            }
        }
    }

    /**
     * Escapes embedded newlines in JSON messages as required by MCP STDIO specification.
     * Messages are delimited by newlines and MUST NOT contain embedded newlines.
     *
     * @param message Original JSON message
     * @return Message with escaped newlines
     */
    private String escapeEmbeddedNewlines(String message) {
        if (message == null) {
            return null;
        }

        // Escape all forms of newlines as per MCP spec
        return message.replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    /**
     * Returns a reactive stream of messages from the MCP Server.
     *
     * @return Flux of JSON-RPC messages received from the server
     */
    public Flux<String> getMessages() {
        return outputSink.asFlux();
    }

    /**
     * Checks if the MCP Server process is running.
     *
     * @return true if the process is running and alive
     */
    public boolean isRunning() {
        return isRunning.get() && process != null && process.isAlive();
    }

    /**
     * Gets the MCP Server configuration.
     *
     * @return The server configuration
     */
    public McpServerConfig getConfig() {
        return config;
    }


    /**
     * Starts the output reader thread that reads JSON-RPC messages from stdout.
     * Messages are read line-by-line as per MCP STDIO specification.
     */
    private void startOutputReader() {
        Mono.fromRunnable(() -> {
                    try {
                        String line;
                        while (isRunning.get() && (line = processReader.readLine()) != null) {
                            logger.debug("Received from MCP Server '{}': {}", config.getName(), line);

                            // Validate that we received a complete JSON message
                            if (isValidJsonMessage(line)) {
                                outputSink.tryEmitNext(line);
                            } else {
                                logger.warn("Received malformed JSON message from MCP Server '{}': {}", 
                                           config.getName(), line);
                            }
                        }
                    } catch (IOException e) {
                        if (isRunning.get()) {
                            logger.error("Error reading from MCP Server '{}' process", config.getName(), e);
                            if (config.getProcess().isRestartEnabled()) {
                                restart();
                            }
                        }
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * Starts the error reader thread to capture stderr from the MCP Server.
     */
    private void startErrorReader() {
        Mono.fromRunnable(() -> {
                    try (BufferedReader errorReader = new BufferedReader(
                            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {

                        String line;
                        while (isRunning.get() && (line = errorReader.readLine()) != null) {
                            logger.warn("MCP Server '{}' STDERR: {}", config.getName(), line);
                        }
                    } catch (IOException e) {
                        if (isRunning.get()) {
                            logger.error("Error reading stderr from MCP Server '{}'", config.getName(), e);
                        }
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * Validates that a line contains a complete JSON message.
     * This is a basic validation to ensure we received proper JSON-RPC.
     *
     * @param line The line to validate
     * @return true if the line appears to be valid JSON
     */
    private boolean isValidJsonMessage(String line) {
        if (line == null || line.trim().isEmpty()) {
            return false;
        }

        try {
            objectMapper.readTree(line);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Restarts the MCP Server process after a failure.
     */
    private void restart() {
        logger.warn("Restarting MCP Server '{}' process", config.getName());
        stop();
        try {
            Thread.sleep(config.getProcess().getRestartDelayMs());
            start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while restarting MCP Server '{}'", config.getName(), e);
        }
    }
}