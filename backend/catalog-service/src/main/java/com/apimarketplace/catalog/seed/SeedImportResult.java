package com.apimarketplace.catalog.seed;

import java.util.List;

public record SeedImportResult(int imported, int skipped, int failed, int userModified, List<String> errors) {
}
