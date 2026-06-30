package com.apimarketplace.auth.domain;

/**
 * Roles that a user can have within an organization.
 */
public enum OrganizationRole {
    /**
     * Full control over the organization. Can delete org, manage billing, invite/remove any member.
     */
    OWNER,

    /**
     * Can manage members and most settings, but cannot delete org or transfer ownership.
     */
    ADMIN,

    /**
     * Standard member with read/write access to organization resources.
     */
    MEMBER,

    /**
     * Read-only access to organization resources.
     */
    VIEWER
}
