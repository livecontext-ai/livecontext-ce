package com.apimarketplace.auth.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Auth logback configuration")
class AuthLogbackConfigurationTest {

    private static final Path LOGBACK_CONFIG = Path.of("src/main/resources/logback-spring.xml");

    @Test
    @DisplayName("k8s profile routes audit events to console without a rolling file appender")
    void k8sProfileRoutesAuditEventsToConsole() throws Exception {
        Element profile = springProfile("k8s");

        assertThat(descendantAppenders(profile, "ch.qos.logback.core.rolling.RollingFileAppender"))
                .isEmpty();

        Element logger = descendantLogger(profile, "AUDIT");
        assertThat(logger.getAttribute("additivity")).isEqualTo("false");
        assertThat(appenderRefs(logger)).containsExactly("AUDIT_CONSOLE_ASYNC");
    }

    @Test
    @DisplayName("non-k8s profile keeps audit events on the dedicated rolling file appender")
    void nonK8sProfileKeepsAuditEventsOnRollingFileAppender() throws Exception {
        Element profile = springProfile("!k8s");

        assertThat(descendantAppenders(profile, "ch.qos.logback.core.rolling.RollingFileAppender"))
                .extracting(appender -> appender.getAttribute("name"))
                .containsExactly("AUDIT_FILE");

        Element logger = descendantLogger(profile, "AUDIT");
        assertThat(logger.getAttribute("additivity")).isEqualTo("false");
        assertThat(appenderRefs(logger)).containsExactly("AUDIT_ASYNC");
    }

    private static Element springProfile(String name) throws Exception {
        List<Element> profiles = directChildren(document().getDocumentElement(), "springProfile");
        return profiles.stream()
                .filter(profile -> name.equals(profile.getAttribute("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing springProfile " + name));
    }

    private static Document document() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        String xml = Files.readString(LOGBACK_CONFIG);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static List<Element> directChildren(Element parent, String tagName) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && tagName.equals(element.getTagName())) {
                children.add(element);
            }
        }
        return children;
    }

    private static List<Element> descendantAppenders(Element root, String className) {
        List<Element> appenders = new ArrayList<>();
        for (Element appender : descendants(root, "appender")) {
            if (className.equals(appender.getAttribute("class"))) {
                appenders.add(appender);
            }
        }
        return appenders;
    }

    private static Element descendantLogger(Element root, String loggerName) {
        return descendants(root, "logger").stream()
                .filter(logger -> loggerName.equals(logger.getAttribute("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing logger " + loggerName));
    }

    private static List<Element> descendants(Element root, String tagName) {
        List<Element> elements = new ArrayList<>();
        collectDescendants(root, tagName, elements);
        return elements;
    }

    private static void collectDescendants(Element parent, String tagName, List<Element> elements) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                if (tagName.equals(element.getTagName())) {
                    elements.add(element);
                }
                collectDescendants(element, tagName, elements);
            }
        }
    }

    private static List<String> appenderRefs(Element logger) {
        return directChildren(logger, "appender-ref").stream()
                .map(ref -> ref.getAttribute("ref"))
                .toList();
    }
}
