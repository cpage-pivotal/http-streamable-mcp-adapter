package org.tanzu.adapter.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Processes environment variables for MCP server configuration.
 * Handles passthrough environment variables from host to subprocess.
 */
@Component
public class EnvironmentVariableProcessor {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentVariableProcessor.class);

    /**
     * Processes environment variables based on configuration.
     * Only handles passthrough variables for security and simplicity.
     *
     * @param config The MCP server configuration
     * @param hostEnvironment The host environment variables (typically System.getenv())
     * @return Map of environment variables to set in the subprocess
     */
    public Map<String, String> processEnvironmentVariables(
            McpServerConfig config, 
            Map<String, String> hostEnvironment) {
        
        Map<String, String> result = new HashMap<>();
        
        if (config.getEnvironment() == null || 
            config.getEnvironment().getPassthrough() == null) {
            logger.debug("No passthrough environment variables configured");
            return result;
        }
        
        logger.debug("Processing {} passthrough environment variables", 
                    config.getEnvironment().getPassthrough().size());
        
        // Process passthrough variables only
        for (McpServerConfig.PassthroughVar var : config.getEnvironment().getPassthrough()) {
            if (var.getSource() == null || var.getSource().trim().isEmpty()) {
                logger.warn("Skipping passthrough variable with empty source");
                continue;
            }
            
            String sourceKey = var.getSource().trim();
            String value = hostEnvironment.get(sourceKey);
            
            if (value != null) {
                // Use target name if specified, otherwise use source name
                String targetKey = (var.getTarget() != null && !var.getTarget().trim().isEmpty()) 
                    ? var.getTarget().trim() 
                    : sourceKey;
                
                result.put(targetKey, value);
                logger.debug("Mapped environment variable: {} -> {}", sourceKey, targetKey);
            } else {
                logger.debug("Host environment variable '{}' not found, skipping", sourceKey);
            }
        }
        
        logger.info("Processed {} environment variables for MCP server '{}'", 
                   result.size(), config.getName());
        
        return result;
    }

    /**
     * Validates that all required environment variables are present.
     *
     * @param config The MCP server configuration
     * @param hostEnvironment The host environment variables
     * @throws IllegalStateException if required environment variables are missing
     */
    public void validateRequiredEnvironmentVariables(
            McpServerConfig config, 
            Map<String, String> hostEnvironment) {
        
        if (config.getValidation() == null || 
            config.getValidation().getRequiredEnvVars() == null) {
            return;
        }
        
        for (String requiredVar : config.getValidation().getRequiredEnvVars()) {
            if (requiredVar == null || requiredVar.trim().isEmpty()) {
                continue;
            }
            
            String varName = requiredVar.trim();
            if (!hostEnvironment.containsKey(varName) || 
                hostEnvironment.get(varName) == null || 
                hostEnvironment.get(varName).trim().isEmpty()) {
                
                throw new IllegalStateException(
                    String.format("Required environment variable '%s' is not set for MCP server '%s'", 
                                varName, config.getName()));
            }
        }
        
        logger.debug("All required environment variables are present for MCP server '{}'", 
                    config.getName());
    }

    /**
     * Processes environment variables using System.getenv() as the host environment.
     *
     * @param config The MCP server configuration
     * @return Map of environment variables to set in the subprocess
     */
    public Map<String, String> processEnvironmentVariables(McpServerConfig config) {
        return processEnvironmentVariables(config, System.getenv());
    }

    /**
     * Validates required environment variables using System.getenv() as the host environment.
     *
     * @param config The MCP server configuration
     * @throws IllegalStateException if required environment variables are missing
     */
    public void validateRequiredEnvironmentVariables(McpServerConfig config) {
        validateRequiredEnvironmentVariables(config, System.getenv());
    }
}