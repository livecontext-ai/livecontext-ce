package com.apimarketplace.catalog.mapping.runner;

import com.apimarketplace.catalog.mapping.generator.MappingGenerationException;
import com.apimarketplace.catalog.mapping.generator.StrictMappingConstraints;
import com.apimarketplace.catalog.mapping.service.MappingGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Command line runner for testing the mapping generator locally.
 * 
 * This runner can be enabled by setting the property mapping.test.enabled=true
 * and will generate a sample mapping for testing purposes.
 */
@Component
@ConditionalOnProperty(name = "mapping.test.enabled", havingValue = "true")
public class MappingGeneratorTestRunner implements CommandLineRunner {
    
    private static final Logger logger = LoggerFactory.getLogger(MappingGeneratorTestRunner.class);
    
    @Autowired
    private MappingGeneratorService mappingGeneratorService;
    
    @Override
    public void run(String... args) throws Exception {
        logger.info("Starting mapping generator test...");
        
        // Sample JSON data (Instagram reels response)
        String sampleJson = """
            {
              "data": {
                "reels_media": [
                  {
                    "items": [
                      {
                        "id": "3692587195553868383",
                        "pk": "3692587195553868383",
                        "user": {
                          "id": "22311116",
                          "username": "gilbert_burns",
                          "full_name": "GILBERT BURNS \\"DURINHO\\" 🇧🇷",
                          "is_private": false,
                          "is_verified": true
                        },
                        "display_url": "https://instagram.fcxj5-1.fna.fbcdn.net/v/t51.2885-19/516297306_18516565198039117_6994273045607875398_n.jpg",
                        "thumbnail_src": "https://instagram.fcxj5-1.fna.fbcdn.net/v/t51.2885-19/516297306_18516565198039117_6994273045607875398_n.jpg",
                        "is_video": true,
                        "has_audio": true,
                        "video_duration": 66.533333,
                        "caption": {
                          "text": "Training hard! 💪 #ufc #mma #training"
                        },
                        "like_count": 1250,
                        "comment_count": 89,
                        "view_count": 15000,
                        "taken_at": 1703123456,
                        "product_type": "reels"
                      }
                    ]
                  }
                ]
              }
            }
            """;
        
        try {
            // Test with default constraints
            logger.info("Testing with default constraints...");
            String mapping1 = mappingGeneratorService.generateStrictMapping(sampleJson);
            logger.info("Generated mapping (default): {}", mapping1);
            
            // Test with specific items path
            logger.info("Testing with specific items path...");
            StrictMappingConstraints constraints = new StrictMappingConstraints("$.data.reels_media[*].items[*]");
            constraints.setMaxFallbacks(3);
            String mapping2 = mappingGeneratorService.generateStrictMapping(sampleJson, constraints);
            logger.info("Generated mapping (with items path): {}", mapping2);
            
            logger.info("Mapping generator test completed successfully!");
            
        } catch (MappingGenerationException e) {
            logger.error("Mapping generation failed: {}", e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error during mapping generation test: {}", e.getMessage(), e);
        }
    }
}

