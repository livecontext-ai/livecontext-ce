package com.apimarketplace.catalog.dto;

import java.util.List;

/**
 * One custom API ({@code apis.source = 'custom'}) referenced by a set of
 * workflow tool identifiers.
 *
 * <p>Custom APIs are tenant-private definitions registered by a user. They are
 * never part of the shipped catalog, so a publication that references one is
 * unusable for anyone else: the acquirer's catalog has no such API and every
 * node built on it fails at run time. Publish-time guards use this DTO to name
 * the offending APIs back to the publisher.
 *
 * @param apiSlug         {@code apis.api_slug} - the prefix of an {@code apiSlug/toolSlug} node id
 * @param apiName         human-readable API name, shown to the publisher
 * @param toolIdentifiers the identifiers from the request that resolved to this API
 */
public record CustomApiRefDTO(
    String apiSlug,
    String apiName,
    List<String> toolIdentifiers
) {}
