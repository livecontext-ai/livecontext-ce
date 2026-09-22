package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.mail.MailTimeouts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the seam between the configured timeouts and the nodes that use them.
 *
 * <p>Without this, the configured value had two independent, silent ways of never reaching a
 * socket: the property could fail to bind (covered by {@code MailTimeoutsBindingTest}), or
 * {@code acceptServices} could fail to carry it into the node. The property builders are static
 * and take the timeouts as an argument, so deleting the assignment in either node left the whole
 * suite green while every mail session ran on the defaults.
 */
@DisplayName("Mail node service wiring")
class MailNodeServiceWiringTest {

    private static final MailTimeouts CUSTOM = new MailTimeouts(1_111, 2_222, 3_333, 4_444, 5_555);

    private static MailTimeouts fieldOf(Object node) throws Exception {
        Field field = node.getClass().getDeclaredField("mailTimeouts");
        field.setAccessible(true);
        return (MailTimeouts) field.get(node);
    }

    private static Core.EmailInboxConfig inboxConfig() {
        return new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "none", null, null,
                null, null, null, false, 0, false, false);
    }

    @Test
    @DisplayName("EmailInboxNode takes the registry's timeouts, and they reach its properties")
    void emailInboxTakesRegistryTimeouts() throws Exception {
        EmailInboxNode node = new EmailInboxNode("core:read_mail", inboxConfig());

        node.acceptServices(ServiceRegistry.builder().mailTimeouts(CUSTOM).build());

        assertEquals(CUSTOM, fieldOf(node), "acceptServices must carry the configured timeouts");
        // And they are the ones a session would actually be built with.
        assertEquals("2222", EmailInboxNode
                .buildMailProperties("imap.example.com", 993, true, fieldOf(node))
                .get("mail.imaps.timeout"));
    }

    @Test
    @DisplayName("SendEmailNode takes the registry's timeouts")
    void sendEmailTakesRegistryTimeouts() throws Exception {
        SendEmailNode node = new SendEmailNode("core:send_mail", null);

        node.acceptServices(ServiceRegistry.builder().mailTimeouts(CUSTOM).build());

        assertEquals(CUSTOM, fieldOf(node));
    }

    @Test
    @DisplayName("a registry that never set them yields the defaults, never null")
    void registryWithoutTimeoutsYieldsDefaults() throws Exception {
        // ~19 test sites build a ServiceRegistry without ever naming mailTimeouts, and the
        // nodes must not have to null-check a pure value on every session.
        ServiceRegistry registry = ServiceRegistry.builder().build();
        assertNotNull(registry.getMailTimeouts());
        assertEquals(MailTimeouts.defaults(), registry.getMailTimeouts());

        EmailInboxNode node = new EmailInboxNode("core:read_mail", inboxConfig());
        node.acceptServices(registry);

        assertEquals(MailTimeouts.defaults(), fieldOf(node));
    }
}
