package com.apimarketplace.catalog.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/catalog")
@ConditionalOnProperty(name = "catalog.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class CatalogSeedController {

    private final CatalogSeedService seedService;

    @PostMapping("/reload")
    public ResponseEntity<SeedImportResult> reload() {
        log.info("Manual catalog seed reload triggered");
        SeedImportResult result = seedService.importAll();
        return ResponseEntity.ok(result);
    }
}
