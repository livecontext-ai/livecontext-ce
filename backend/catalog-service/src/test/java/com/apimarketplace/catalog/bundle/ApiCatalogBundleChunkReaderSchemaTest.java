package com.apimarketplace.catalog.bundle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where the sliced reader looks for its table.
 *
 * <p>The qualifier comes from the schema Hibernate is configured with, and every
 * production environment happens to want {@code catalog}. That makes the two
 * ways of getting this wrong invisible: a typo in the property NAME and a blank
 * value both fall back to {@code catalog}, which is correct today - and would
 * stay correct until the day it is not.
 */
@DisplayName("ApiCatalogBundleChunkReader - schema qualification")
class ApiCatalogBundleChunkReaderSchemaTest {

    private static final String PROPERTY = "spring.jpa.properties.hibernate.default_schema";

    @Test
    @DisplayName("a blank or absent value falls back to catalog, which is what the CE monolith needs")
    void aBlankSchemaFallsBackToCatalog() {
        // The monolith deliberately leaves the property empty and resolves its
        // dozen schemas through search_path; the bundle table still lives in
        // catalog there.
        assertThat(schemaUsedBy(new ApiCatalogBundleChunkReader(mock(JdbcTemplate.class), "")))
                .isEqualTo("catalog");
        assertThat(schemaUsedBy(new ApiCatalogBundleChunkReader(mock(JdbcTemplate.class), "   ")))
                .isEqualTo("catalog");
        assertThat(schemaUsedBy(new ApiCatalogBundleChunkReader(mock(JdbcTemplate.class), null)))
                .isEqualTo("catalog");
    }

    @Test
    @DisplayName("a configured schema is honoured, or a test could never reach its own tables")
    void aConfiguredSchemaIsUsed() {
        assertThat(schemaUsedBy(new ApiCatalogBundleChunkReader(mock(JdbcTemplate.class), " tenant_x ")))
                .isEqualTo("tenant_x");
    }

    @Test
    @DisplayName("a value that is not a plain identifier is refused at startup, not at the first download")
    void aSchemaNeedingQuotesIsRefusedEarly() {
        // It goes into the statement text, where a bind parameter cannot stand in
        // for it. Failing here names the misconfiguration; failing later would
        // surface as a broken download with a syntax error nobody connects to a
        // property.
        assertThatThrownBy(() -> new ApiCatalogBundleChunkReader(mock(JdbcTemplate.class), "my-schema"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("my-schema");
        assertThatThrownBy(() -> new ApiCatalogBundleChunkReader(mock(JdbcTemplate.class), "cat alog"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the reader binds the property NAME production sets - a typo there would fall back "
            + "to catalog and look right forever")
    void theReaderReadsTheRealPropertyName() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("schema", Map.of(PROPERTY, "tenant_from_property")));
            // The reader itself, so Spring resolves the @Value on ITS constructor.
            // Building it from a @Bean method that reads the property would only
            // prove this test knows the property name.
            context.register(ReaderConfig.class, ApiCatalogBundleChunkReader.class);
            context.refresh();

            assertThat(schemaUsedBy(context.getBean(ApiCatalogBundleChunkReader.class)))
                    .as("the reader must take its qualifier from " + PROPERTY)
                    .isEqualTo("tenant_from_property");
        }
    }

    /** The schema a reader actually puts in its SQL, read off a real statement. */
    private static String schemaUsedBy(ApiCatalogBundleChunkReader reader) {
        JdbcTemplate template = jdbcOf(reader);
        when(template.queryForList(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn(List.of());

        reader.payloadLength(1L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).queryForList(sql.capture(), any(Class.class), any(Object[].class));
        String from = sql.getValue().substring(sql.getValue().indexOf("FROM ") + "FROM ".length());
        return from.substring(0, from.indexOf(".api_catalog_bundles")).trim();
    }

    private static JdbcTemplate jdbcOf(ApiCatalogBundleChunkReader reader) {
        try {
            java.lang.reflect.Field field = ApiCatalogBundleChunkReader.class.getDeclaredField("jdbcTemplate");
            field.setAccessible(true);
            return (JdbcTemplate) field.get(reader);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not reach the reader's template", e);
        }
    }

    @Configuration
    static class ReaderConfig {
        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }
}
