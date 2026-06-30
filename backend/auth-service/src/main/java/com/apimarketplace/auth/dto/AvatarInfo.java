package com.apimarketplace.auth.dto;

import java.time.Instant;

public record AvatarInfo(
    String id,
    String url,
    String mimeType,
    Instant createdAt,
    boolean active
) {}
