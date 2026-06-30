package com.apimarketplace.orchestrator.services.template;

import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto-projects a property when the target is a {@code List<Map>} whose first
 * non-null element is a Map containing the requested key.
 *
 * <p>Rationale: after a SpEL selection ({@code .?[...]}, {@code .$[...]}),
 * the result is always a {@code List} - even when a single match was found.
 * Writing {@code headers.?[name == 'Subject'].value} therefore fails with
 * {@code EL1008E} because {@code ArrayList} has no {@code value} property.
 *
 * <p>The canonical SpEL fixes are {@code .^[...].value} (first match, single
 * object) or {@code .?[...].![value]} (explicit projection). This accessor
 * offers a third option that matches the JSONPath mental model: treat
 * {@code list.prop} as {@code list.![prop]} when the list is a collection of
 * Maps holding that key.
 *
 * <p>Read-only. Does not enable reflection or any SpEL feature that was not
 * already reachable through {@link org.springframework.expression.spel.support.StandardEvaluationContext}
 * - a malicious template could already write {@code .![prop]} explicitly.
 *
 * <p>Safety rails:
 * <ul>
 *   <li>Only activates for {@link List} targets.</li>
 *   <li>Requires the first non-null element to be a {@link Map} whose keys are
 *       {@link String} and that contains the requested key. This avoids
 *       hijacking property lookups on e.g. {@code List<User>} where
 *       {@code user.firstName} is a real bean property that SpEL should
 *       resolve via reflection.</li>
 *   <li>Does not override native List properties ({@code size}, {@code empty},
 *       …) - those are resolved first by SpEL's built-in accessor, so control
 *       never reaches this class for them.</li>
 * </ul>
 */
public class ListMapProjectionPropertyAccessor implements PropertyAccessor {

    /**
     * Property names that are native Java Bean properties on {@link List} -
     * resolved by SpEL's reflective accessor. We must decline these so that
     * {@code list.empty} still evaluates to {@code isEmpty()} ({@code Boolean})
     * and {@code list.size} still evaluates to {@code size()} ({@code int}),
     * rather than being hijacked as projections.
     *
     * <p>Unlike the bean accessor, a custom {@code PropertyAccessor} registered
     * via {@code addPropertyAccessor()} is tried <b>before</b> the default
     * reflective resolver, so we have to gate it explicitly.
     */
    private static final Set<String> RESERVED_LIST_PROPERTIES = Set.of(
        "empty", "size", "class"
    );

    @Override
    public Class<?>[] getSpecificTargetClasses() {
        return new Class<?>[] { List.class };
    }

    @Override
    public boolean canRead(EvaluationContext context, Object target, String name) {
        if (!(target instanceof List<?> list) || RESERVED_LIST_PROPERTIES.contains(name)) {
            return false;
        }
        // Empty list: projecting any non-reserved key yields an empty list -
        // honest JSONPath semantics for "no match → no values".
        if (list.isEmpty()) {
            return true;
        }
        return firstMapWithKey(list, name) != null;
    }

    @Override
    public TypedValue read(EvaluationContext context, Object target, String name) throws AccessException {
        List<?> list = (List<?>) target;
        List<Object> projected = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                projected.add(map.get(name));
            } else {
                projected.add(null);
            }
        }
        return new TypedValue(projected);
    }

    @Override
    public boolean canWrite(EvaluationContext context, Object target, String name) {
        return false;
    }

    @Override
    public void write(EvaluationContext context, Object target, String name, Object newValue) throws AccessException {
        throw new AccessException("Cannot write projected property '" + name + "' on a List");
    }

    /**
     * Returns the first {@link Map} in the list whose keys are Strings and
     * which contains {@code key}, or {@code null} if none qualifies. Used both
     * to decide {@link #canRead} and to guard against accidental projection on
     * arbitrary {@code List<Object>}.
     */
    private static Map<?, ?> firstMapWithKey(List<?> list, String key) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && !map.isEmpty()) {
                Object probe = map.keySet().iterator().next();
                if (probe instanceof String && map.containsKey(key)) {
                    return map;
                }
            }
        }
        return null;
    }
}
