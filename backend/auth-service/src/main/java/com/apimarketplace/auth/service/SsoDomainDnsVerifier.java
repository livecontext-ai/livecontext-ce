package com.apimarketplace.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * Reads the TXT records that prove a workspace controls an email domain.
 *
 * <p>The record lives on a dedicated label ({@value #RECORD_PREFIX}&lt;domain&gt;) rather than
 * the apex, so publishing it never touches the domain's SPF or other apex TXT values.
 */
@Component
public class SsoDomainDnsVerifier {

    private static final Logger log = LoggerFactory.getLogger(SsoDomainDnsVerifier.class);

    public static final String RECORD_PREFIX = "_livecontext-sso.";
    public static final String VALUE_PREFIX = "livecontext-sso-verification=";

    public String recordName(String domain) {
        return RECORD_PREFIX + domain;
    }

    public String expectedValue(String token) {
        return VALUE_PREFIX + token;
    }

    /** True when a TXT record on {@link #recordName} carries exactly {@link #expectedValue}. */
    public boolean isVerified(String domain, String token) {
        String expected = expectedValue(token);
        return lookupTxt(recordName(domain)).stream().anyMatch(expected::equals);
    }

    /**
     * TXT values of {@code name}, quotes removed and multi-string records joined. An absent name
     * is an empty list; any other DNS failure is thrown, so a resolver outage never reads as
     * "not published".
     */
    protected List<String> lookupTxt(String name) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "2");
        List<String> values = new ArrayList<>();
        try {
            InitialDirContext ctx = new InitialDirContext(env);
            try {
                Attributes attrs = ctx.getAttributes("dns:/" + name, new String[]{"TXT"});
                Attribute txt = attrs.get("TXT");
                if (txt == null) {
                    return values;
                }
                NamingEnumeration<?> all = txt.getAll();
                while (all.hasMore()) {
                    values.add(normalize(String.valueOf(all.next())));
                }
            } finally {
                ctx.close();
            }
        } catch (NameNotFoundException e) {
            return values;
        } catch (javax.naming.NamingException e) {
            log.warn("SSO domain TXT lookup failed for {}: {}", name, e.getMessage());
            throw new IllegalStateException("DNS lookup failed for " + name, e);
        }
        return values;
    }

    /** JNDI returns {@code "part1" "part2"} for a split record; the value is their concatenation. */
    static String normalize(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.contains("\"")) {
            return trimmed;
        }
        StringBuilder out = new StringBuilder();
        boolean inQuotes = false;
        for (char c : trimmed.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (inQuotes) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
