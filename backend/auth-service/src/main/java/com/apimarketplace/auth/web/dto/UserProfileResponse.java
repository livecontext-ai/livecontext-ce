package com.apimarketplace.auth.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * DTO pour la reponse du profil utilisateur
 */
public class UserProfileResponse {

    @JsonProperty("userId")
    private Long userId;

    @JsonProperty("username")
    private String username;

    @JsonProperty("email")
    private String email;

    @JsonProperty("firstName")
    private String firstName;

    @JsonProperty("lastName")
    private String lastName;

    @JsonProperty("avatarUrl")
    private String avatarUrl;

    @JsonProperty("planCode")
    private String planCode;

    @JsonProperty("planName")
    private String planName;

    @JsonProperty("subscriptionStatus")
    private String subscriptionStatus;

    @JsonProperty("currentPeriodStart")
    private LocalDateTime currentPeriodStart;

    @JsonProperty("currentPeriodEnd")
    private LocalDateTime currentPeriodEnd;

    @JsonProperty("cancelAtPeriodEnd")
    private Boolean cancelAtPeriodEnd;

    @JsonProperty("tools")
    private java.util.List<java.util.Map<String, Object>> tools;

    @JsonProperty("error")
    private String error;

    // Constructeur par defaut
    public UserProfileResponse() {}

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final UserProfileResponse response = new UserProfileResponse();

        public Builder userId(Long userId) {
            response.userId = userId;
            return this;
        }

        public Builder username(String username) {
            response.username = username;
            return this;
        }

        public Builder email(String email) {
            response.email = email;
            return this;
        }

        public Builder firstName(String firstName) {
            response.firstName = firstName;
            return this;
        }

        public Builder lastName(String lastName) {
            response.lastName = lastName;
            return this;
        }

        public Builder avatarUrl(String avatarUrl) {
            response.avatarUrl = avatarUrl;
            return this;
        }

        public Builder planCode(String planCode) {
            response.planCode = planCode;
            return this;
        }

        public Builder planName(String planName) {
            response.planName = planName;
            return this;
        }

        public Builder subscriptionStatus(String subscriptionStatus) {
            response.subscriptionStatus = subscriptionStatus;
            return this;
        }

        public Builder currentPeriodStart(LocalDateTime currentPeriodStart) {
            response.currentPeriodStart = currentPeriodStart;
            return this;
        }

        public Builder currentPeriodEnd(LocalDateTime currentPeriodEnd) {
            response.currentPeriodEnd = currentPeriodEnd;
            return this;
        }

        public Builder cancelAtPeriodEnd(Boolean cancelAtPeriodEnd) {
            response.cancelAtPeriodEnd = cancelAtPeriodEnd;
            return this;
        }

        public Builder tools(java.util.List<java.util.Map<String, Object>> tools) {
            response.tools = tools;
            return this;
        }

        public Builder error(String error) {
            response.error = error;
            return this;
        }

        public UserProfileResponse build() {
            return response;
        }
    }

    // Getters et Setters
    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getPlanCode() {
        return planCode;
    }

    public void setPlanCode(String planCode) {
        this.planCode = planCode;
    }

    public String getPlanName() {
        return planName;
    }

    public void setPlanName(String planName) {
        this.planName = planName;
    }

    public String getSubscriptionStatus() {
        return subscriptionStatus;
    }

    public void setSubscriptionStatus(String subscriptionStatus) {
        this.subscriptionStatus = subscriptionStatus;
    }

    public LocalDateTime getCurrentPeriodStart() {
        return currentPeriodStart;
    }

    public void setCurrentPeriodStart(LocalDateTime currentPeriodStart) {
        this.currentPeriodStart = currentPeriodStart;
    }

    public LocalDateTime getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    public void setCurrentPeriodEnd(LocalDateTime currentPeriodEnd) {
        this.currentPeriodEnd = currentPeriodEnd;
    }

    public Boolean getCancelAtPeriodEnd() {
        return cancelAtPeriodEnd;
    }

    public void setCancelAtPeriodEnd(Boolean cancelAtPeriodEnd) {
        this.cancelAtPeriodEnd = cancelAtPeriodEnd;
    }

    public java.util.List<java.util.Map<String, Object>> getTools() {
        return tools;
    }

    public void setTools(java.util.List<java.util.Map<String, Object>> tools) {
        this.tools = tools;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
