package com.apimarketplace.orchestrator.services.state.elide;

import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializer;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;

import java.io.IOException;

/**
 * Custom JsonSerializer for {@link EpochState} that conditionally omits the
 * {@code runningNodeIds} field based on a per-tenant flag (P2.3 deliverable).
 *
 * <p>Extends {@link BeanSerializer} (concrete subclass of
 * {@link BeanSerializerBase}) so the {@code with*} factory chain inherited from
 * Jackson works without re-implementation. Overrides only the
 * {@link #serializeFields(Object, JsonGenerator, SerializerProvider)} hook to
 * conditionally skip the {@code runningNodeIds} property.
 *
 * <p>Caller protocol - set the tenant attribute per write:
 * <pre>{@code
 * String json = stateSnapshotMapper.writer()
 *     .withAttribute(EpochStateRunningElideSerializer.TENANT_ATTRIBUTE, run.getTenantId())
 *     .writeValueAsString(snapshot);
 * }</pre>
 *
 * <p>Failure modes:
 * <ul>
 *   <li>{@code TENANT_ATTRIBUTE} missing → {@code tenantId == null} → fail-OPEN to
 *       "do not elide". Preserves data on misconfigured callers (existing JSONB
 *       behavior). ArchUnit/integration tests must catch the omission, but the
 *       runtime gracefully degrades.</li>
 *   <li>{@code flagResolver} returns false → field included (default behavior).</li>
 *   <li>{@code flagResolver} throws → fail-OPEN to include the field. The
 *       {@link TenantElideFlagResolver} contract (P2.3.3) requires O(1)
 *       in-memory lookup with no expected throws, but defensive - never break
 *       a state save on a flag-lookup hiccup.</li>
 * </ul>
 *
 * <p>Per design rev12 §3.5 the flag is per-tenant and ramps independently. The
 * serializer NEVER caches the flag value - the lookup is per-write so a flip
 * takes effect on the next save without restart.
 */
public class EpochStateRunningElideSerializer extends BeanSerializer {

    /**
     * Per-call serializer attribute name. Must be set by the caller via
     * {@code ObjectWriter.withAttribute(...)} BEFORE invoking
     * {@code writeValueAsBytes/String/...}. Reading runs through
     * {@link SerializerProvider#getAttribute(Object)} during serialize().
     */
    public static final String TENANT_ATTRIBUTE = "elideRunningNodes.tenantId";

    private static final String RUNNING_FIELD = "runningNodeIds";

    private final TenantElideFlagResolver flagResolver;

    public EpochStateRunningElideSerializer(BeanSerializerBase src, TenantElideFlagResolver flagResolver) {
        super(src);
        this.flagResolver = flagResolver;
    }

    @Override
    protected void serializeFields(Object bean, JsonGenerator gen, SerializerProvider provider) throws IOException {
        boolean elide = shouldElide(provider);
        BeanPropertyWriter[] writers = (_filteredProps != null && provider.getActiveView() != null)
                ? _filteredProps
                : _props;
        for (BeanPropertyWriter prop : writers) {
            if (prop == null) continue;
            if (elide && RUNNING_FIELD.equals(prop.getName())) {
                continue;
            }
            try {
                prop.serializeAsField(bean, gen, provider);
            } catch (Exception e) {
                wrapAndThrow(provider, e, bean, prop.getName());
            }
        }
    }

    private boolean shouldElide(SerializerProvider provider) {
        Object attr = provider.getAttribute(TENANT_ATTRIBUTE);
        if (!(attr instanceof String tenantId) || tenantId.isEmpty()) {
            return false;
        }
        try {
            return flagResolver.isElideEnabled(tenantId);
        } catch (Exception e) {
            return false;
        }
    }
}
