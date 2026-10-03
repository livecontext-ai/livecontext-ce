package com.example.inprocess;

import org.springframework.web.client.RestTemplate;

/**
 * A bean-shaped object deliberately OUTSIDE {@code com.apimarketplace}, used by
 * {@code InProcessCallStampingTest} to pin that the bean walk does not open other people's objects.
 *
 * <p>It has to live in its own file: two top-level classes in one source file still share that
 * file's package, so the "foreign" case cannot be expressed next to the fixtures it contrasts with.
 */
public class ForeignHolder {

    public final RestTemplate restTemplate = new RestTemplate();
}
