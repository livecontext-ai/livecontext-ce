package com.apimarketplace.catalog.config;

import com.apimarketplace.common.web.NoRedirectSimpleClientHttpRequestFactory;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;

import java.sql.SQLException;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * Configuration for dual data access (JPA + JDBC)
 */
@Configuration
@EnableJdbcRepositories(
    basePackages = {
        "com.apimarketplace.catalog.repository",
        "com.apimarketplace.catalog.seed"
    }
)
public class DataConfig extends AbstractJdbcConfiguration {

    @Value("${http.client.connect-timeout:5000}")
    private int connectTimeout;

    @Value("${http.client.read-timeout:30000}")
    private int readTimeout;

    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Centralized RestTemplate bean with configured timeouts.
     * All services should inject this bean instead of creating new RestTemplate instances.
     */
    @Bean
    public RestTemplate restTemplate() {
        NoRedirectSimpleClientHttpRequestFactory factory = new NoRedirectSimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }

    @Override
    public JdbcCustomConversions jdbcCustomConversions() {
        return new JdbcCustomConversions(List.of(
                PgObjectToStringConverter.INSTANCE,
                PgObjectToJsonbStringConverter.INSTANCE,
                JsonbStringToPgObjectConverter.INSTANCE
        ));
    }

    @ReadingConverter
    enum PgObjectToStringConverter implements Converter<PGobject, String> {
        INSTANCE;

        @Override
        public String convert(PGobject source) {
            return source == null ? null : source.getValue();
        }
    }

    @ReadingConverter
    enum PgObjectToJsonbStringConverter implements Converter<PGobject, JsonbString> {
        INSTANCE;

        @Override
        public JsonbString convert(PGobject source) {
            return source == null ? null : new JsonbString(source.getValue());
        }
    }

    @WritingConverter
    enum JsonbStringToPgObjectConverter implements Converter<JsonbString, PGobject> {
        INSTANCE;

        @Override
        public PGobject convert(JsonbString source) {
            if (source == null) {
                return null;
            }
            try {
                PGobject pg = new PGobject();
                pg.setType("jsonb");
                pg.setValue(source.value());
                return pg;
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to wrap JsonbString as PGobject", e);
            }
        }
    }
}
