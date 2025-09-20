package org.tanzu.adapter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Configuration properties for generic MCP server setup.
 * Supports any STDIO-based MCP server through flexible configuration.
 */
@Component
@ConfigurationProperties(prefix = "mcp.server")
public class McpServerConfig {

    private String name = "generic-mcp-server";
    private String description = "Generic MCP Server";
    private String executable = "./bin/mcp-server";
    private List<String> args = new ArrayList<>();
    private String workingDirectory = ".";
    private EnvironmentConfig environment = new EnvironmentConfig();
    private ProcessConfig process = new ProcessConfig();
    private ValidationConfig validation = new ValidationConfig();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = executable;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args != null ? args : new ArrayList<>();
    }

    /**
     * Sets args from a comma-separated string for environment variable support.
     * Supports both single arguments and comma-separated multiple arguments.
     * Examples: "stdio" or "stdio,--config,config.json"
     */
    public void setArgs(String argsString) {
        if (argsString == null || argsString.trim().isEmpty()) {
            this.args = new ArrayList<>();
            return;
        }
        
        this.args = Arrays.stream(argsString.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public EnvironmentConfig getEnvironment() {
        return environment;
    }

    public void setEnvironment(EnvironmentConfig environment) {
        this.environment = environment != null ? environment : new EnvironmentConfig();
    }

    public ProcessConfig getProcess() {
        return process;
    }

    public void setProcess(ProcessConfig process) {
        this.process = process != null ? process : new ProcessConfig();
    }

    public ValidationConfig getValidation() {
        return validation;
    }

    public void setValidation(ValidationConfig validation) {
        this.validation = validation != null ? validation : new ValidationConfig();
    }

    /**
     * Environment configuration for the MCP server process.
     */
    public static class EnvironmentConfig {
        private List<PassthroughVar> passthrough = new ArrayList<>();

        public List<PassthroughVar> getPassthrough() {
            return passthrough;
        }

        public void setPassthrough(List<PassthroughVar> passthrough) {
            this.passthrough = passthrough != null ? passthrough : new ArrayList<>();
        }
    }

    /**
     * Process management configuration for the MCP server.
     */
    public static class ProcessConfig {
        private boolean restartEnabled = true;
        private long restartDelayMs = 5000;
        private boolean healthCheckEnabled = true;
        private long healthCheckIntervalMs = 30000;
        private long startupTimeoutMs = 60000;

        public boolean isRestartEnabled() {
            return restartEnabled;
        }

        public void setRestartEnabled(boolean restartEnabled) {
            this.restartEnabled = restartEnabled;
        }

        public long getRestartDelayMs() {
            return restartDelayMs;
        }

        public void setRestartDelayMs(long restartDelayMs) {
            this.restartDelayMs = restartDelayMs;
        }

        public boolean isHealthCheckEnabled() {
            return healthCheckEnabled;
        }

        public void setHealthCheckEnabled(boolean healthCheckEnabled) {
            this.healthCheckEnabled = healthCheckEnabled;
        }

        public long getHealthCheckIntervalMs() {
            return healthCheckIntervalMs;
        }

        public void setHealthCheckIntervalMs(long healthCheckIntervalMs) {
            this.healthCheckIntervalMs = healthCheckIntervalMs;
        }

        public long getStartupTimeoutMs() {
            return startupTimeoutMs;
        }

        public void setStartupTimeoutMs(long startupTimeoutMs) {
            this.startupTimeoutMs = startupTimeoutMs;
        }
    }

    /**
     * Validation configuration for the MCP server.
     */
    public static class ValidationConfig {
        private List<String> requiredEnvVars = new ArrayList<>();

        public List<String> getRequiredEnvVars() {
            return requiredEnvVars;
        }

        public void setRequiredEnvVars(List<String> requiredEnvVars) {
            this.requiredEnvVars = requiredEnvVars != null ? requiredEnvVars : new ArrayList<>();
        }
    }

    /**
     * Configuration for environment variable passthrough from host to subprocess.
     */
    public static class PassthroughVar {
        private String source;
        private String target;

        public PassthroughVar() {}

        public PassthroughVar(String source, String target) {
            this.source = source;
            this.target = target;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }
    }
}