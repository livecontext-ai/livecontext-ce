package com.apimarketplace.catalog.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * DTO pour les mises a jour d'outils - contient seulement les proprietes autorisees
 */
public class ToolUpdateDto {
    
    // Proprietes de base de l'outil
    private String name;
    
    private String description;
    
    private String method;
    
    private String endpoint;

    private String protocol;
    
    private String status;
    
    private Boolean isActive;
    
    // Proprietes de configuration
    private String pricing;
    
    private Integer rateLimit;
    
    // Parametres
    private List<Map<String, Object>> pathParameters;
    
    private List<Map<String, Object>> queryParameters;
    
    private List<Map<String, Object>> headers;
    
    private List<Map<String, Object>> bodyParams;
    
    // Schemas et reponses
    private Map<String, Object> bodySchema;
    
    private Map<String, Object> response;

    private Map<String, Object> sqlConfig;

    private Map<String, Object> amqpConfig;

    private Map<String, Object> kafkaConfig;

    private Map<String, Object> mqttConfig;

    private Map<String, Object> redisConfig;

    private Map<String, Object> runtimeMetadata;
    
    // Statut de test
    private String testStatus;
    
    // Metadonnees
    private String version;
    
    // Constructeurs
    public ToolUpdateDto() {}
    
    // Getters et Setters
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getMethod() {
        return method;
    }
    
    public void setMethod(String method) {
        this.method = method;
    }
    
    public String getEndpoint() {
        return endpoint;
    }
    
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    
    public String getPricing() {
        return pricing;
    }
    
    public void setPricing(String pricing) {
        this.pricing = pricing;
    }
    
    public Integer getRateLimit() {
        return rateLimit;
    }
    
    public void setRateLimit(Integer rateLimit) {
        this.rateLimit = rateLimit;
    }
    
    public List<Map<String, Object>> getPathParameters() {
        return pathParameters;
    }
    
    public void setPathParameters(List<Map<String, Object>> pathParameters) {
        this.pathParameters = pathParameters;
    }
    
    public List<Map<String, Object>> getQueryParameters() {
        return queryParameters;
    }
    
    public void setQueryParameters(List<Map<String, Object>> queryParameters) {
        this.queryParameters = queryParameters;
    }
    
    public List<Map<String, Object>> getHeaders() {
        return headers;
    }
    
    public void setHeaders(List<Map<String, Object>> headers) {
        this.headers = headers;
    }
    
    public List<Map<String, Object>> getBodyParams() {
        return bodyParams;
    }
    
    public void setBodyParams(List<Map<String, Object>> bodyParams) {
        this.bodyParams = bodyParams;
    }
    
    
    public Map<String, Object> getBodySchema() {
        return bodySchema;
    }
    
    public void setBodySchema(Map<String, Object> bodySchema) {
        this.bodySchema = bodySchema;
    }
    
    public Map<String, Object> getResponse() {
        return response;
    }
    
    public void setResponse(Map<String, Object> response) {
        this.response = response;
    }

    public Map<String, Object> getSqlConfig() {
        return sqlConfig;
    }

    public void setSqlConfig(Map<String, Object> sqlConfig) {
        this.sqlConfig = sqlConfig;
    }

    public Map<String, Object> getAmqpConfig() {
        return amqpConfig;
    }

    public void setAmqpConfig(Map<String, Object> amqpConfig) {
        this.amqpConfig = amqpConfig;
    }

    public Map<String, Object> getKafkaConfig() {
        return kafkaConfig;
    }

    public void setKafkaConfig(Map<String, Object> kafkaConfig) {
        this.kafkaConfig = kafkaConfig;
    }

    public Map<String, Object> getMqttConfig() {
        return mqttConfig;
    }

    public void setMqttConfig(Map<String, Object> mqttConfig) {
        this.mqttConfig = mqttConfig;
    }

    public Map<String, Object> getRedisConfig() {
        return redisConfig;
    }

    public void setRedisConfig(Map<String, Object> redisConfig) {
        this.redisConfig = redisConfig;
    }

    public Map<String, Object> getRuntimeMetadata() {
        return runtimeMetadata;
    }

    public void setRuntimeMetadata(Map<String, Object> runtimeMetadata) {
        this.runtimeMetadata = runtimeMetadata;
    }
    
    public String getTestStatus() {
        return testStatus;
    }
    
    public void setTestStatus(String testStatus) {
        this.testStatus = testStatus;
    }
    
    
    public String getVersion() {
        return version;
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    @Override
    public String toString() {
        return "ToolUpdateDto{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", method='" + method + '\'' +
                ", endpoint='" + endpoint + '\'' +
                ", protocol='" + protocol + '\'' +
                ", status='" + status + '\'' +
                ", isActive=" + isActive +
                ", pricing='" + pricing + '\'' +
                ", rateLimit=" + rateLimit +
                ", pathParameters=" + pathParameters +
                ", queryParameters=" + queryParameters +
                ", headers=" + headers +
                ", bodyParams=" + bodyParams +
                ", bodySchema=" + bodySchema +
                ", response=" + response +
                ", sqlConfig=" + sqlConfig +
                ", amqpConfig=" + amqpConfig +
                ", kafkaConfig=" + kafkaConfig +
                ", mqttConfig=" + mqttConfig +
                ", redisConfig=" + redisConfig +
                ", testStatus='" + testStatus + '\'' +
                ", version='" + version + '\'' +
                '}';
    }
}
