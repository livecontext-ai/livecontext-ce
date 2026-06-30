package com.apimarketplace.credential.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * All decrypted credential fields as a map.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CredentialDataMapDto {

    private Map<String, String> data;

    public CredentialDataMapDto() {}

    public CredentialDataMapDto(Map<String, String> data) {
        this.data = data;
    }

    public Map<String, String> getData() { return data; }
    public void setData(Map<String, String> data) { this.data = data; }
}
