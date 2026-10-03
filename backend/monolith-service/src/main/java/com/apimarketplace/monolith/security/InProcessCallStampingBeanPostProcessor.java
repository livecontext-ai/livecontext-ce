package com.apimarketplace.monolith.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Attaches {@link InProcessCallInterceptor} to every {@code RestTemplate} this application can
 * reach through its own beans (LC-032).
 *
 * <p><b>Why a bean walk and not a list of call sites.</b> The monolith builds an outbound
 * {@code X-User-ID} in roughly seventy places across seventeen modules, and only a minority of
 * them share a helper. Editing each one arms the filter's check exactly once and then rots: the
 * next client added anywhere in the tree silently fails to stamp, and the symptom is a 401 on an
 * internal hop that no unit test exercises. Walking the graph covers all of them at once and keeps
 * covering new ones, and it lives in the monolith, which is the only edition where loopback calls
 * re-enter the same JVM.
 *
 * <p><b>Why fields and not just {@code RestTemplate} beans.</b> Almost none of the clients expose
 * their template as a bean: {@code AuthClient}, {@code TriggerClient}, {@code CredentialClient}
 * and the rest do {@code new RestTemplate(...)} in their constructor and keep it in a private
 * field. Some are one level deeper again ({@code DatasourceRowEventListener} holds a
 * {@code TriggerClient} that holds the template), so the walk descends through fields whose value
 * is one of this product's own types, to a small fixed depth.
 *
 * <p><b>What it deliberately does not reach.</b> A template constructed inside a method body and
 * never stored is invisible here. Every such instance in the tree today addresses an external host
 * (the user-supplied URL of an HTTP-request node, the cloud relay clients, the provider SDKs),
 * which is precisely the set that must NOT carry the secret. The failure mode if a future one
 * points at loopback is a refusal, never a grant.
 */
public class InProcessCallStampingBeanPostProcessor implements BeanPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(InProcessCallStampingBeanPostProcessor.class);

    /**
     * How deep to follow this product's own types looking for a template. Two is enough for every
     * client in the tree today (bean -> client -> template is depth two); three leaves room for one
     * more layer of wrapping without turning the walk into a graph traversal of the whole context.
     */
    private static final int MAX_DEPTH = 3;

    private static final String APPLICATION_PACKAGE = "com.apimarketplace.";

    private final InProcessCallTarget target;
    private final InProcessCallInterceptor interceptor;
    private final InProcessExchangeFilter exchangeFilter;
    private final Set<Object> stamped =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    public InProcessCallStampingBeanPostProcessor(InProcessCallTarget target) {
        this.target = target;
        this.interceptor = new InProcessCallInterceptor(target);
        this.exchangeFilter = new InProcessExchangeFilter(target);
    }

    public InProcessCallTarget target() {
        return target;
    }

    /** Number of distinct templates stamped so far. Read by the startup log line and by tests. */
    public int stampedCount() {
        return stamped.size();
    }

    @Override
    public int getOrder() {
        // Ahead of the auto-proxy creator, so the object handed here is the raw bean whose fields
        // hold the templates rather than a proxy that has none of them.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        // After initialization rather than before, so a template created in @PostConstruct is
        // covered too.
        try {
            stampReachable(unwrap(bean), 0, new IdentityHashMap<>());
        } catch (Exception e) {
            // Never let the walk stop a context from starting: the worst case of a miss is a
            // refused internal hop, the worst case of a throw here is an install that will not boot.
            log.warn("In-process call stamping skipped bean '{}': {}", beanName, e.toString());
        }
        return bean;
    }

    private static Object unwrap(Object bean) {
        if (bean instanceof Advised advised) {
            try {
                Object targetObject = advised.getTargetSource().getTarget();
                if (targetObject != null) {
                    return targetObject;
                }
            } catch (Exception e) {
                // Fall through to the proxy itself.
            }
        }
        return bean;
    }

    private void stampReachable(Object candidate, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (candidate == null || depth > MAX_DEPTH) {
            return;
        }
        if (candidate instanceof RestTemplate restTemplate) {
            stamp(restTemplate);
            return;
        }
        if (candidate instanceof WebClient.Builder builder) {
            stamp(builder);
            return;
        }
        if (candidate instanceof RestClient.Builder builder) {
            stamp(builder);
            return;
        }
        Class<?> type = candidate.getClass();
        if (!isApplicationType(type)) {
            return;
        }
        if (seen.put(candidate, Boolean.TRUE) != null) {
            return;
        }
        for (Class<?> c = type; c != null && isApplicationType(c); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                Class<?> declared = field.getType();
                // Only two kinds of field are worth opening: one that can hold a template
                // (RestTemplate itself, or a supertype of it such as RestOperations or Object), or
                // one of our own objects that may hold one further down. Everything else (entities,
                // config records, JDK types) is skipped so the walk stays cheap and touches nothing
                // it has no business touching.
                if (!declared.isAssignableFrom(RestTemplate.class)
                        && !RestTemplate.class.isAssignableFrom(declared)
                        && !WebClient.Builder.class.isAssignableFrom(declared)
                        && !RestClient.class.isAssignableFrom(declared)
                        && !RestClient.Builder.class.isAssignableFrom(declared)
                        && !isApplicationType(declared)) {
                    continue;
                }
                Object value = readField(field, candidate);
                if (value instanceof RestClient restClient) {
                    stampField(field, candidate, restClient);
                } else if (value != null) {
                    stampReachable(value, depth + 1, seen);
                }
            }
        }
    }

    private static Object readField(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (RuntimeException | ReflectiveOperationException e) {
            return null;
        }
    }

    private static boolean isApplicationType(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isArray()) {
            return false;
        }
        String name = type.getName();
        return name.startsWith(APPLICATION_PACKAGE);
    }

    private void stamp(RestTemplate restTemplate) {
        if (!stamped.add(restTemplate)) {
            return;
        }
        if (!restTemplate.getInterceptors().contains(interceptor)) {
            restTemplate.getInterceptors().add(interceptor);
        }
    }

    /**
     * A built {@code RestClient} cannot be given an interceptor, so its FIELD is replaced by a copy
     * that carries one. This is how {@code OrchestratorCascadeClient} (interface delete),
     * {@code OrchestratorInterfaceMembershipClient} (LC-037 share binding) and
     * {@code OrchestratorSubWorkflowLineageClient} reach the orchestrator in CE: each builds its
     * client from the static {@code RestClient.builder()} in its constructor, and before this
     * branch every one of those hops went out bare and was answered 404, so deleting an interface
     * failed on every self-hosted install. The copy keeps the original's base URL, request factory
     * and timeouts ({@code mutate()}), and the interceptor only stamps a call addressed to this
     * JVM's own loopback port, so a client that talks to an external host is unchanged in effect.
     */
    private void stampField(Field field, Object owner, RestClient restClient) {
        if (stamped.contains(restClient)) {
            return;
        }
        RestClient stampedClient = restClient.mutate().requestInterceptor(interceptor).build();
        try {
            field.setAccessible(true);
            field.set(owner, stampedClient);
            stamped.add(stampedClient);
        } catch (RuntimeException | ReflectiveOperationException e) {
            log.warn("In-process call stamping could not replace RestClient field {}.{}: {}",
                    owner.getClass().getName(), field.getName(), e.toString());
        }
    }

    private void stamp(RestClient.Builder builder) {
        if (!stamped.add(builder)) {
            return;
        }
        builder.requestInterceptor(interceptor);
    }

    /**
     * The reactive half. A {@code WebClient} cannot be modified once built, so the filter goes on
     * the BUILDER - which is also why this is not a {@code WebClientCustomizer}: this application
     * declares its own builder beans, and those replace the auto-configured one a customizer would
     * have been applied to.
     */
    private void stamp(WebClient.Builder builder) {
        if (!stamped.add(builder)) {
            return;
        }
        builder.filter(exchangeFilter.filterFunction());
    }
}
