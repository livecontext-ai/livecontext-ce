package com.apimarketplace.orchestrator.services.template;

import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;

import java.util.Collection;
import java.util.Map;

/**
 * Custom SpEL PropertyAccessor that makes ".length" work on Collections and Maps.
 * SpEL already supports .length natively on String and arrays.
 *
 * This prevents a common mistake where LLMs (or users) write ".length" instead of ".size()"
 * for Java Collections. In SpEL, ArrayList.length silently returns null, causing conditions
 * like "int(messages?.length) > 0" to always evaluate to false.
 *
 * With this accessor registered, ".length" returns the size for any collection-like type.
 */
public class CollectionLengthPropertyAccessor implements PropertyAccessor {

    private static final String LENGTH = "length";

    @Override
    public Class<?>[] getSpecificTargetClasses() {
        return new Class<?>[] { Collection.class, Map.class };
    }

    @Override
    public boolean canRead(EvaluationContext context, Object target, String name) throws AccessException {
        return LENGTH.equals(name) && (target instanceof Collection || target instanceof Map);
    }

    @Override
    public TypedValue read(EvaluationContext context, Object target, String name) throws AccessException {
        if (target instanceof Collection<?> col) {
            return new TypedValue(col.size());
        }
        if (target instanceof Map<?, ?> map) {
            return new TypedValue(map.size());
        }
        throw new AccessException("Cannot read property '" + name + "' from " + target.getClass());
    }

    @Override
    public boolean canWrite(EvaluationContext context, Object target, String name) throws AccessException {
        return false;
    }

    @Override
    public void write(EvaluationContext context, Object target, String name, Object newValue) throws AccessException {
        throw new AccessException("Cannot write to property 'length'");
    }
}
