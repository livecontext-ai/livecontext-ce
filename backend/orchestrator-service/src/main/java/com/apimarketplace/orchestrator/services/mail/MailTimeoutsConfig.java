package com.apimarketplace.orchestrator.services.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code workflow.mail.*} into the {@link MailTimeouts} the mail nodes use.
 *
 * <p><b>It exists as its own class so the binding can be TESTED.</b> These five values used to
 * sit as {@code @Value} fields on {@code ExecutionServiceInjector}, a bean with dozens of
 * collaborators that no unit test can boot. A property name typed wrong there would leave every
 * timeout at its fallback, the feature would be silently non-configurable, and every test would
 * still pass, because the tests hand the nodes a {@code MailTimeouts} directly. Hoisting the
 * binding into a class small enough to boot on its own is what lets
 * {@code MailTimeoutsBindingTest} assert that the SHIPPED application.yml actually arrives here.
 *
 * <p><b>An absent property coerces rather than failing the boot, and that is a deliberate
 * divergence</b> from auth-service's {@code MailTransportSecurityValidator}, which defaults its
 * own {@code @Value}s to 0 and throws, so that deleting its yml block cannot go unnoticed. The
 * opposite choice is right here because a value that has a sane default should not be able to
 * take an install down.
 *
 * <p>What that costs, stated exactly: {@link MailTimeouts} logs a WARN naming the property it
 * substituted, so a substitution is visible and a healthy boot is silent. It does NOT tell a
 * typo from a deliberate absence - both emit the same line - so the WARN buys "something here is
 * not being read", not a diagnosis. Telling the two apart is the job of
 * {@code MailTimeoutsBindingTest}, which fails on a key that exists on only one side.
 */
@Configuration
public class MailTimeoutsConfig {

    // Defaults are 0 on purpose: MailTimeouts owns the real numbers, so the property and the
    // record can never disagree about what "unset" means. 0 is also what an absent property
    // yields, which is exactly the case MailTimeouts coerces and warns about.
    @Value("${workflow.mail.imap.connect-timeout-ms:0}")
    private int imapConnectMs;

    @Value("${workflow.mail.imap.read-timeout-ms:0}")
    private int imapReadMs;

    @Value("${workflow.mail.smtp.connect-timeout-ms:0}")
    private int smtpConnectMs;

    @Value("${workflow.mail.smtp.read-timeout-ms:0}")
    private int smtpReadMs;

    @Value("${workflow.mail.smtp.write-timeout-ms:0}")
    private int smtpWriteMs;

    @Bean
    public MailTimeouts mailTimeouts() {
        return new MailTimeouts(imapConnectMs, imapReadMs, smtpConnectMs, smtpReadMs, smtpWriteMs);
    }
}
