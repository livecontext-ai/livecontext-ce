package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Node specification for the SSH node.
 *
 * Output schema:
 * - node_type: string ("SSH")
 * - success: boolean (true if exit code is 0)
 * - exit_code: number (process exit code)
 * - stdout: string (standard output)
 * - stderr: string (standard error)
 * - host: string (connected host)
 * - command: string (executed command)
 * - duration_ms: number (execution time in milliseconds)
 */
@Component
public class SshNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("SSH")
            .label("SSH")
            .category("core")
            .variablePrefix("core")
            .description("Execute commands on remote servers via SSH")
            .terminal(false)
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("node_type")
                    .type("string")
                    .description("Always 'SSH'")
                    .defaultValue("SSH")
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("True if the command exited with code 0")
                    .build(),
                OutputFieldDef.builder()
                    .key("exit_code")
                    .type("number")
                    .description("Process exit code (0 = success)")
                    .build(),
                OutputFieldDef.builder()
                    .key("stdout")
                    .type("string")
                    .description("Standard output from the command")
                    .build(),
                OutputFieldDef.builder()
                    .key("stderr")
                    .type("string")
                    .description("Standard error output from the command")
                    .build(),
                OutputFieldDef.builder()
                    .key("host")
                    .type("string")
                    .description("The SSH host that was connected to")
                    .build(),
                OutputFieldDef.builder()
                    .key("command")
                    .type("string")
                    .description("The command that was executed")
                    .build(),
                OutputFieldDef.builder()
                    .key("duration_ms")
                    .type("number")
                    .description("Total execution time in milliseconds")
                    .build()
            ))
            .keywords(List.of("ssh", "remote", "command", "shell", "server", "execute"))
            .build();
    }
}
