package com.apimarketplace.auth.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter to store OrganizationRole as lowercase string in database.
 * This matches the CHECK constraint in the database which expects lowercase values.
 */
@Converter(autoApply = false)
public class OrganizationRoleConverter implements AttributeConverter<OrganizationRole, String> {

    @Override
    public String convertToDatabaseColumn(OrganizationRole role) {
        if (role == null) {
            return null;
        }
        return role.name().toLowerCase();
    }

    @Override
    public OrganizationRole convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return null;
        }
        return OrganizationRole.valueOf(dbData.toUpperCase());
    }
}
