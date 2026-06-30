package com.apimarketplace.auth.service.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REGRESSION GUARD for the auth-service boot failure caught by live E2E (2026-05-30).
 *
 * <p>{@link EnterpriseLicenseService} declares TWO constructors: the {@code @Value}-injected
 * public one and a package-private {@code Clock}-injecting one used by unit tests. Without
 * {@code @Autowired} on the public constructor, Spring cannot choose a constructor, falls back
 * to a (non-existent) no-arg constructor, and auth-service fails to start with:
 * <pre>BeanCreationException ... No default constructor found / NoSuchMethodException: &lt;init&gt;()</pre>
 *
 * <p>The plain unit tests ({@code EnterpriseLicenseServiceTest}) build the service directly via
 * {@code new}, so they never exercise Spring's constructor resolution - the gap only surfaces at
 * live Spring boot. This test boots a minimal context to instantiate the bean exactly as
 * auth-service does. It FAILS on the pre-fix code (no {@code @Autowired}) and passes once the
 * annotation is present.
 */
class EnterpriseLicenseServiceSpringWiringTest {

    @Test
    @DisplayName("Spring instantiates EnterpriseLicenseService via its @Autowired constructor (no default-ctor fallback)")
    void springInstantiatesViaAutowiredConstructor() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(PropertySourcesPlaceholderConfigurer.class);
            ctx.registerBean(ObjectMapper.class);
            ctx.registerBean(EnterpriseLicenseService.class);

            // Pre-fix: refresh() throws BeanCreationException (no resolvable constructor).
            ctx.refresh();

            EnterpriseLicenseService service = ctx.getBean(EnterpriseLicenseService.class);
            assertThat(service).isNotNull();
            // Safe-by-default: no license configured (empty @Value defaults) -> inactive, no throw.
            assertThat(service.currentStatus()).isNotNull();
        }
    }
}
