package com.apimarketplace.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import jakarta.annotation.PostConstruct;

/**
 * Publishes {@code platform.logging.payloads} to {@link PayloadLogSafety} so the sinks that are
 * not Spring beans read the same switch as the ones that are.
 *
 * <p>Registered as an AUTO-configuration (see {@code AutoConfiguration.imports}) rather than a
 * component: no service component-scans {@code com.apimarketplace.common.logging}, so a plain
 * {@code @Configuration} here would be a switch nothing ever reads. Auto-configuration reaches
 * every Spring Boot service that has common-lib on its classpath, the CE monolith included.
 *
 * <p>The setting is deliberately absent from every {@code application.yml}, so it is off
 * everywhere by default. Logging a WARN at startup when it is enabled is the cheapest way to
 * make sure nobody leaves a debugging session switched on in an environment that processes
 * real user data.
 *
 * <p>Introduced for LC-009 / LC-034 (CASA remediation).
 */
@AutoConfiguration
public class PayloadLoggingConfig {

    private static final Logger log = LoggerFactory.getLogger(PayloadLoggingConfig.class);

    @Value(PayloadLogSafety.PAYLOAD_LOGGING_PLACEHOLDER)
    private boolean logPayloads;

    @PostConstruct
    void publish() {
        PayloadLogSafety.setPayloadLoggingEnabled(logPayloads);
        if (logPayloads) {
            log.warn("{}=true: tool parameters, results and request bodies will be written to the "
                            + "application log IN FULL. This is for LOCAL debugging only. Google's "
                            + "Limited Use requirements forbid it for restricted-scope data, so it "
                            + "must be false in any environment handling real user data.",
                    PayloadLogSafety.PAYLOAD_LOGGING_PROPERTY);
        }
    }
}
