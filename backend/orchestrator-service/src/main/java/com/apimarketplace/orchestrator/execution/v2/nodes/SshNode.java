package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SSH node - Execute commands on remote servers via SSH.
 *
 * Connection credentials (host, port, username, password/privateKey) are loaded
 * from the credential system (Settings > Credentials > SSH) when a credentialId
 * is configured. Falls back to inline config fields for backward compatibility.
 *
 * SECURITY: Password and private key values are NEVER logged.
 */
public class SshNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(SshNode.class);
    private static final String SSH_INTEGRATION = "ssh";
    private static final int DEFAULT_TIMEOUT = 30000;
    private static final int BUFFER_SIZE = 4096;
    private static final int MAX_OUTPUT_SIZE = 10 * 1024 * 1024; // 10 MB cap per stream

    private final Core.SshConfig config;
    private CredentialClient credentialClient;

    public SshNode(String nodeId, Core.SshConfig config) {
        super(nodeId, NodeType.SSH);
        this.config = config;
    }

    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.credentialClient = registry.getCredentialClient();
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();

        if (config == null) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "SSH configuration is required.",
                Map.of("node_type", "SSH", "resolved_params", Map.of()),
                System.currentTimeMillis() - startTime);
        }

        // Resolve connection from credential system or inline config
        String host, username, password, privateKey, authMethod;
        int port;

        Long credentialId = config.credentialId();
        if (credentialId != null && credentialClient != null) {
            Optional<CredentialSummaryDto> cred = credentialClient.getCredentialById(context.tenantId(), credentialId);
            if (cred.isEmpty()) {
                logger.warn("SSH credential {} not found, falling back to default", credentialId);
                cred = credentialClient.getDefaultCredential(context.tenantId(), SSH_INTEGRATION);
            }
            if (cred.isEmpty()) {
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "SSH credential not found. Configure an SSH credential and set it on this node before running.",
                    Map.of("node_type", "SSH", "resolved_params", Map.of()),
                    System.currentTimeMillis() - startTime);
            }
            Map<String, Object> data = cred.get().getCredentialData();
            host = getString(data, "host");
            port = getInt(data, "port", 22);
            username = getString(data, "username");
            authMethod = getString(data, "auth_method");
            password = getString(data, "password");
            privateKey = getString(data, "private_key");
        } else {
            // Fallback: inline config (backward compatibility)
            host = resolveTemplateString(config.host(), context);
            port = config.port();
            username = resolveTemplateString(config.username(), context);
            authMethod = config.authMethod();
            password = resolveTemplateString(config.password(), context);
            privateKey = resolveTemplateString(config.privateKey(), context);
        }

        // Operation-specific fields always come from config
        String command = resolveTemplateString(config.command(), context);
        int timeout = config.timeout() != null ? config.timeout() : DEFAULT_TIMEOUT;
        if (authMethod == null) authMethod = "password";

        // Snapshot resolved configuration for the inspector "Resolved parameters" panel.
        // Built once, used in every exit path. Password / private key never leak in here -
        // they must not land in workflow_step_data.input_data.
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        resolvedParams.put("host", host);
        resolvedParams.put("port", port);
        resolvedParams.put("username", username);
        resolvedParams.put("authMethod", authMethod);
        resolvedParams.put("command", command);
        resolvedParams.put("timeout", timeout);

        logger.info("SSH node executing: nodeId={}, host={}, port={}, username={}, command={}, itemId={}",
            nodeId, host, port, username, command, context.itemId());

        // Validate required fields
        if (host == null || host.isBlank()) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "SSH: 'host' is required. Configure it in the SSH credential.",
                buildErrorResult(host, command, startTime, resolvedParams),
                System.currentTimeMillis() - startTime);
        }
        if (command == null || command.isBlank()) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "SSH: 'command' is required.",
                buildErrorResult(host, command, startTime, resolvedParams),
                System.currentTimeMillis() - startTime);
        }

        Session session = null;
        ChannelExec channel = null;
        try {
            JSch jsch = new JSch();

            // Configure authentication
            if ("privatekey".equals(authMethod) || "private_key".equals(authMethod)) {
                if (privateKey != null && !privateKey.isBlank()) {
                    jsch.addIdentity("ssh-key", privateKey.getBytes(StandardCharsets.UTF_8), null, null);
                }
            }

            session = jsch.getSession(username, host, port);

            if ("password".equals(authMethod)) {
                if (password != null) {
                    session.setPassword(password);
                }
            }

            // Disable strict host key checking for workflow use
            session.setConfig("StrictHostKeyChecking", "no");
            session.setTimeout(timeout);
            session.connect(timeout);

            // Execute command - manual stream reads with size cap (no setOutputStream to avoid double-write)
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            // JSch streams must be obtained before connect(); fast commands can close
            // before the stream wrappers are attached, yielding an empty stdout.
            InputStream stdoutIn = channel.getInputStream();
            InputStream stderrIn = channel.getErrStream();
            channel.connect(timeout);

            ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();
            byte[] buf = new byte[BUFFER_SIZE];
            boolean truncated = false;

            while (!channel.isClosed()) {
                // Read stdout
                while (stdoutIn.available() > 0) {
                    int len = stdoutIn.read(buf);
                    if (len < 0) break;
                    if (stdoutStream.size() + len <= MAX_OUTPUT_SIZE) {
                        stdoutStream.write(buf, 0, len);
                    } else {
                        truncated = true;
                    }
                }
                // Read stderr
                while (stderrIn.available() > 0) {
                    int len = stderrIn.read(buf);
                    if (len < 0) break;
                    if (stderrStream.size() + len <= MAX_OUTPUT_SIZE) {
                        stderrStream.write(buf, 0, len);
                    } else {
                        truncated = true;
                    }
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Read remaining data
            while (stdoutIn.available() > 0) {
                int len = stdoutIn.read(buf);
                if (len < 0) break;
                if (stdoutStream.size() + len <= MAX_OUTPUT_SIZE) {
                    stdoutStream.write(buf, 0, len);
                } else {
                    truncated = true;
                }
            }
            while (stderrIn.available() > 0) {
                int len = stderrIn.read(buf);
                if (len < 0) break;
                if (stderrStream.size() + len <= MAX_OUTPUT_SIZE) {
                    stderrStream.write(buf, 0, len);
                } else {
                    truncated = true;
                }
            }

            int exitCode = channel.getExitStatus();
            String stdout = stdoutStream.toString(StandardCharsets.UTF_8);
            String stderr = stderrStream.toString(StandardCharsets.UTF_8);
            if (truncated) {
                stderr = stderr + "\n[WARNING] Output truncated - exceeded " + MAX_OUTPUT_SIZE + " bytes limit.";
            }
            long durationMs = System.currentTimeMillis() - startTime;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("node_type", "SSH");
            result.put("resolved_params", resolvedParams);
            result.put("success", exitCode == 0);
            result.put("exit_code", exitCode);
            result.put("stdout", stdout);
            result.put("stderr", stderr);
            result.put("host", host);
            result.put("command", command);
            result.put("duration_ms", durationMs);
            if (truncated) {
                result.put("truncated", true);
            }

            logger.info("SSH node completed: nodeId={}, host={}, exitCode={}, durationMs={}",
                nodeId, host, exitCode, durationMs);

            return successWithMetadata(result, context);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            logger.error("SSH node failed: nodeId={}, host={}, error={}", nodeId, host, e.getMessage());

            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("node_type", "SSH");
            errorResult.put("resolved_params", resolvedParams);
            errorResult.put("success", false);
            errorResult.put("host", host);
            errorResult.put("command", command);
            errorResult.put("duration_ms", durationMs);

            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), errorResult, durationMs);

        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    private Map<String, Object> buildErrorResult(String host, String command, long startTime, Map<String, Object> resolvedParams) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_type", "SSH");
        result.put("resolved_params", resolvedParams != null ? resolvedParams : Map.of());
        result.put("success", false);
        result.put("host", host);
        result.put("command", command);
        result.put("duration_ms", System.currentTimeMillis() - startTime);
        return result;
    }

    public Core.SshConfig getConfig() {
        return config;
    }

    public static class Builder {
        private String nodeId;
        private Core.SshConfig config;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder sshConfig(Core.SshConfig config) { this.config = config; return this; }
        public Builder templateAdapter(Object adapter) { return this; }
        public SshNode build() { return new SshNode(nodeId, config); }
    }

    public static Builder builder() { return new Builder(); }
}
