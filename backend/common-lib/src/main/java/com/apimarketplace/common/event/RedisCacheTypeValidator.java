package com.apimarketplace.common.event;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Allow-list of the polymorphic type ids a value cached through a service's
 * {@code RedisTemplate<String, Object>} may materialise (LC-081).
 *
 * <p>Those templates enable Jackson default typing ({@code NON_FINAL}, {@code @class} property),
 * so every cached value names the class Jackson instantiates on read. They used
 * {@code LaissezFaireSubTypeValidator}, which accepts ANY class name: anyone able to write a key
 * into Redis could make the next reader instantiate an arbitrary class on the classpath (the
 * classic deserialisation-gadget primitive).
 *
 * <p>The allow-list covers what the templates actually store, verified against every
 * {@code RedisTemplate<String, Object>} user:
 * <ul>
 *   <li>orchestrator {@code WorkflowBuilderSessionStore}: the first-party
 *       {@code WorkflowBuilderSession} and its nested types, whose free-form
 *       {@code Map<String, Object>} node data holds JDK containers, strings, numbers
 *       ({@code BigDecimal}/{@code BigInteger} included) and {@code java.time} values;</li>
 *   <li>orchestrator {@code ChatDispatchService} and agent-service {@code WidgetSessionService}:
 *       hashes of plain strings;</li>
 *   <li>catalog-service {@code ResponseCache}: a parsed third-party API response body, i.e.
 *       JDK maps/lists/numbers, a Jackson tree node, or a byte array.</li>
 * </ul>
 * Everything else (commons-collections, c3p0, JNDI/JDBC row sets, Spring, Groovy, {@code java.io},
 * {@code java.net}, {@code javax.*}) is refused.
 *
 * <p>Defence in depth: the broad {@code java.util.} / {@code java.lang.} prefixes are carved
 * back for the packages and classes that have no business in a cache value and are the usual
 * gadget building blocks (reflection, class loading, processes, threads, logging and preference
 * back-ends). The carve-out is checked FIRST, including for the element type of an array.
 */
public final class RedisCacheTypeValidator {

    private static final Pattern ALLOWED_ARRAYS = Pattern.compile(
            "\\[+(?:[BCDFIJSZ]|L(?:com\\.apimarketplace\\.|java\\.(?:util|lang|time|math)\\.|com\\.fasterxml\\.jackson\\.databind\\.node\\.)[^;]+;)");

    /** Denied even though an allowed prefix covers them. Prefix match on the class name. */
    static final List<String> DENIED_PREFIXES = List.of(
            "java.util.logging.",
            "java.util.prefs.",
            "java.util.jar.",
            "java.util.zip.",
            "java.util.spi.",
            "java.util.ServiceLoader",
            "java.util.Timer",
            "java.util.concurrent.Executor",
            "java.util.concurrent.ThreadPoolExecutor",
            "java.util.concurrent.ScheduledThreadPoolExecutor",
            "java.util.concurrent.ForkJoin",
            "java.lang.reflect.",
            "java.lang.invoke.",
            "java.lang.ref.",
            "java.lang.instrument.",
            "java.lang.management.",
            "java.lang.module.",
            "java.lang.annotation.",
            "java.lang.Process",
            "java.lang.Thread",
            "java.lang.Class",
            "java.lang.Module",
            "java.lang.StackWalker",
            "java.lang.SecurityManager");

    /** Denied by exact name, where a prefix would catch legitimate siblings. */
    static final Set<String> DENIED_EXACT = Set.of(
            "java.lang.Runtime",
            "java.lang.System",
            "java.lang.Package",
            "java.lang.RuntimePermission");

    private RedisCacheTypeValidator() {
    }

    /** The shared validator; see the class javadoc for why each entry is present. */
    public static PolymorphicTypeValidator create() {
        PolymorphicTypeValidator allowList = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.apimarketplace.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.lang.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.math.")
                .allowIfSubType("com.fasterxml.jackson.databind.node.")
                // Arrays only of primitives or of the element types allowed above. A blanket
                // allowIfSubTypeIsArray() would admit an array of a FINAL outside class, whose
                // elements carry no type id of their own and so are never validated.
                .allowIfSubType(ALLOWED_ARRAYS)
                .build();
        return new DenyFirst(allowList);
    }

    /** True when the class name (or, for an array, its element class name) is carved out. */
    static boolean isDenied(String className) {
        if (className == null) {
            return true;
        }
        String name = className;
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') {
            dims++;
        }
        if (dims > 0) {
            name = name.substring(dims);
            if (name.startsWith("L") && name.endsWith(";")) {
                name = name.substring(1, name.length() - 1);
            }
        }
        if (DENIED_EXACT.contains(name)) {
            return true;
        }
        for (String prefix : DENIED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Applies the carve-out before the allow-list. */
    private static final class DenyFirst extends PolymorphicTypeValidator.Base {
        private static final long serialVersionUID = 1L;

        private final PolymorphicTypeValidator delegate;

        DenyFirst(PolymorphicTypeValidator delegate) {
            this.delegate = delegate;
        }

        @Override
        public Validity validateBaseType(MapperConfig<?> config, JavaType baseType) {
            return delegate.validateBaseType(config, baseType);
        }

        @Override
        public Validity validateSubClassName(MapperConfig<?> config, JavaType baseType, String subClassName)
                throws JsonMappingException {
            if (isDenied(subClassName)) {
                return Validity.DENIED;
            }
            return delegate.validateSubClassName(config, baseType, subClassName);
        }

        @Override
        public Validity validateSubType(MapperConfig<?> config, JavaType baseType, JavaType subType)
                throws JsonMappingException {
            if (isDenied(subType.getRawClass().getName())) {
                return Validity.DENIED;
            }
            return delegate.validateSubType(config, baseType, subType);
        }
    }

}
