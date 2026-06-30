package com.apimarketplace.orchestrator.services.state.elide;

import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Jackson module that installs {@link EpochStateRunningElideSerializer} on the
 * default bean serializer for {@link EpochState} (P2.3 deliverable).
 *
 * <p>Registered ONLY on the dedicated {@code stateSnapshotMapper} bean - see
 * {@code StateSnapshotMapperConfig}. Other ObjectMappers in the application
 * (canonicalizer mapper §7.4, agent-tool mapper, default Spring mapper) MUST
 * NOT register this module - they have their own purposes and would lose data
 * they actually need.
 *
 * <p>The module wraps the default bean serializer rather than replacing it
 * outright so non-elided fields serialize byte-for-byte identically to today.
 * The wrapped serializer only intercepts the {@code runningNodeIds} field
 * write conditionally based on the per-call tenant attribute.
 */
public class EpochStateRunningElideModule extends SimpleModule {

    private final TenantElideFlagResolver flagResolver;

    public EpochStateRunningElideModule(TenantElideFlagResolver flagResolver) {
        super("EpochStateRunningElideModule");
        this.flagResolver = flagResolver;
    }

    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.addBeanSerializerModifier(new BeanSerializerModifier() {
            @Override
            public JsonSerializer<?> modifySerializer(
                    SerializationConfig config,
                    com.fasterxml.jackson.databind.BeanDescription beanDesc,
                    JsonSerializer<?> serializer) {
                if (EpochState.class.equals(beanDesc.getBeanClass())
                        && serializer instanceof BeanSerializerBase base) {
                    return new EpochStateRunningElideSerializer(base, flagResolver);
                }
                return serializer;
            }
        });
    }
}
