package com.apimarketplace.conversation.entity.converter;

import com.apimarketplace.conversation.entity.Message.MessageRole;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Case-insensitive {@link MessageRole} ↔ DB column converter.
 *
 * <p>Replaces the default {@code @Enumerated(EnumType.STRING)} mapping which
 * called {@code Enum.valueOf("user")} and threw because the enum constants
 * are uppercase ({@code USER}, {@code ASSISTANT}, …) while historical rows
 * carry lowercase values ({@code "user"}, {@code "assistant"}). The mix is
 * permanent - the DB has both cases (legacy + recent inserts) - so the
 * converter delegates to {@link MessageRole#fromString} which is itself
 * case-insensitive. New writes are normalised to lowercase to keep the
 * column readable for ad-hoc queries.
 */
@Converter(autoApply = false)
public class MessageRoleConverter implements AttributeConverter<MessageRole, String> {

    @Override
    public String convertToDatabaseColumn(MessageRole role) {
        return role == null ? null : role.getValue();
    }

    @Override
    public MessageRole convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) return null;
        return MessageRole.fromString(dbValue);
    }
}
