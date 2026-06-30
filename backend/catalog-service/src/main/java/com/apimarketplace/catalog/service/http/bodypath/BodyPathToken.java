package com.apimarketplace.catalog.service.http.bodypath;

public sealed interface BodyPathToken {

    String key();

    record Literal(String key) implements BodyPathToken {}

    record IndexedArray(String key, int index) implements BodyPathToken {}

    record MappedArray(String key) implements BodyPathToken {}
}
