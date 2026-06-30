package com.apimarketplace.catalog.service.http.bodypath;

public sealed class BodyPathException extends RuntimeException
        permits BodyPathException.Grammar,
                BodyPathException.Conflict,
                BodyPathException.Arity,
                BodyPathException.SizeMismatch {

    private final String path;

    protected BodyPathException(String path, String message) {
        super("bodyPath '" + path + "': " + message);
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static final class Grammar extends BodyPathException {
        public Grammar(String path, String reason) {
            super(path, "grammar error - " + reason);
        }
    }

    public static final class Conflict extends BodyPathException {
        public Conflict(String path, String segment, String expected, String actual) {
            super(path, "conflict at segment '" + segment + "' - expected " + expected
                    + ", found " + actual);
        }
    }

    public static final class Arity extends BodyPathException {
        public Arity(String path, String segment, String expected, String actual) {
            super(path, "arity mismatch at '" + segment + "' - expected " + expected
                    + ", got " + actual);
        }
    }

    public static final class SizeMismatch extends BodyPathException {
        public SizeMismatch(String path, String segment, int expectedSize, int actualSize) {
            super(path, "array size mismatch at '" + segment + "[]' - expected " + expectedSize
                    + " (from earlier param), got " + actualSize);
        }
    }
}
