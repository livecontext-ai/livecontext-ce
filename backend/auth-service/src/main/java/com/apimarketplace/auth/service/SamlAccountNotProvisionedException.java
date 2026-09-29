package com.apimarketplace.auth.service;

/**
 * A workspace SAML login reached an account that this workspace's identity provider did not
 * create (a password, Google or GitHub account Keycloak linked by email, or an account created
 * by another workspace's IdP). Always refused: see
 * {@link OrganizationSamlLoginService#ensureMembershipForIdentityProvider}.
 */
public class SamlAccountNotProvisionedException extends SamlMembershipException {

    public SamlAccountNotProvisionedException(String message) {
        super(message);
    }
}
