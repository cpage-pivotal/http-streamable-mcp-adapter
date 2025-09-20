package org.tanzu.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

/**
 * Security validation component for MCP Streamable HTTP transport.
 * Validates Origin headers and Protocol Version headers according to MCP specification.
 */
@Component
public class SecurityValidator {

    private static final Logger logger = LoggerFactory.getLogger(SecurityValidator.class);

    // Allowed origins for CORS validation
    private final Set<String> allowedOrigins = Set.of("localhost", "127.0.0.1");

    // Supported MCP protocol versions
    private final Set<String> supportedProtocolVersions = Set.of("2025-06-18", "2025-03-26");

    /**
     * Validates the Origin header for CORS compliance.
     *
     * @param origin The Origin header value
     * @return true if the origin is allowed, false otherwise
     */
    public boolean validateOrigin(String origin) {
        if (origin == null) {
            // Allow null origin for non-browser clients (e.g., curl, Postman)
            logger.debug("Origin header is null - allowing for non-browser client");
            return true;
        }

        try {
            URI originUri = URI.create(origin);
            String host = originUri.getHost();

            boolean isAllowed = allowedOrigins.contains(host) || isLocalhost(host);

            if (isAllowed) {
                logger.debug("Origin validation passed for: {}", origin);
            } else {
                logger.warn("Origin validation failed for: {}", origin);
            }

            return isAllowed;
        } catch (Exception e) {
            logger.error("Invalid origin format: {}", origin, e);
            return false;
        }
    }

    /**
     * Validates the MCP-Protocol-Version header.
     *
     * @param version The MCP-Protocol-Version header value
     * @return true if the version is supported, false otherwise
     */
    public boolean validateProtocolVersion(String version) {
        if (version == null) {
            // Default to latest version per spec if not provided
            logger.debug("Protocol version not provided - defaulting to 2025-06-18");
            return true;
        }

        boolean isSupported = supportedProtocolVersions.contains(version);

        if (isSupported) {
            logger.debug("Protocol version validation passed for: {}", version);
        } else {
            logger.warn("Unsupported protocol version: {}", version);
        }

        return isSupported;
    }

    /**
     * Checks if the given host is localhost.
     *
     * @param host The hostname to check
     * @return true if the host is localhost
     */
    private boolean isLocalhost(String host) {
        return "localhost".equals(host) ||
               "127.0.0.1".equals(host) ||
               "::1".equals(host) ||
               host != null && host.startsWith("127.") ||
               host != null && host.startsWith("192.168.") ||
               host != null && host.startsWith("10.") ||
               host != null && (host.startsWith("172.") && isPrivateClassB(host));
    }

    /**
     * Checks if the host is in the private Class B range (172.16.0.0 to 172.31.255.255).
     */
    private boolean isPrivateClassB(String host) {
        try {
            String[] parts = host.split("\\.");
            if (parts.length >= 2) {
                int secondOctet = Integer.parseInt(parts[1]);
                return secondOctet >= 16 && secondOctet <= 31;
            }
        } catch (NumberFormatException e) {
            // Invalid format, not a private Class B address
        }
        return false;
    }

    /**
     * Gets the set of allowed origins.
     *
     * @return Set of allowed origin hosts
     */
    public Set<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    /**
     * Gets the set of supported protocol versions.
     *
     * @return Set of supported MCP protocol versions
     */
    public Set<String> getSupportedProtocolVersions() {
        return supportedProtocolVersions;
    }
}