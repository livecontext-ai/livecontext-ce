package com.apimarketplace.catalog.util;

/**
 * Utility class for classifying search scores quality
 */
public class SearchScoreClassifier {
    
    /**
     * Quality levels for search results
     */
    public enum Quality {
        EXCELLENT("Excellent", "🏆"),
        VERY_GOOD("Very Good", "✨"),
        GOOD("Good", "✅"),
        AVERAGE("Average", "🔶"),
        POOR("Poor", "❌");
        
        private final String label;
        private final String emoji;
        
        Quality(String label, String emoji) {
            this.label = label;
            this.emoji = emoji;
        }
        
        public String getLabel() {
            return label;
        }
        
        public String getEmoji() {
            return emoji;
        }
        
        public String getDisplayName() {
            return emoji + " " + label;
        }
    }
    
    /**
     * Classify RRF (Reciprocal Rank Fusion) score quality
     * Based on theoretical analysis with k=60
     * 
     * @param rrfScore The RRF score to classify
     * @param hasKnnResults Whether KNN results contributed to the score
     * @param hasLexicalResults Whether lexical results contributed to the score
     * @return Quality classification
     */
    public static Quality classifyRRFScore(double rrfScore, boolean hasKnnResults, boolean hasLexicalResults) {
        // Apply confidence multiplier based on fusion type
        double adjustedScore = rrfScore;
        if (hasKnnResults && hasLexicalResults) {
            // Both methods agree - high confidence, no adjustment needed
        } else if (hasKnnResults && !hasLexicalResults) {
            // KNN only - slightly lower confidence
            adjustedScore *= 0.95;
        } else if (!hasKnnResults && hasLexicalResults) {
            // Lexical only - good confidence for exact matches
            adjustedScore *= 1.05;
        }
        
        // Theoretical thresholds based on RRF formula analysis
        if (adjustedScore >= 0.032) {
            return Quality.EXCELLENT;  // Top 1% - Rank 1+1 fusion or better
        } else if (adjustedScore >= 0.030) {
            return Quality.VERY_GOOD;  // Top 5% - Rank 1+3 to 1+5 fusion
        } else if (adjustedScore >= 0.028) {
            return Quality.GOOD;       // Top 10% - Rank 1+6 to 1+10 fusion
        } else if (adjustedScore >= 0.025) {
            return Quality.AVERAGE;    // Top 25% - Moderate ranks
        } else {
            return Quality.POOR;       // Bottom 75% - High ranks or weak similarity
        }
    }
    
    /**
     * Classify BM25 lexical search score quality
     * 
     * @param bm25Score The BM25 score to classify
     * @return Quality classification
     */
    public static Quality classifyBM25Score(double bm25Score) {
        if (bm25Score >= 0.8) {
            return Quality.EXCELLENT;  // Very high lexical similarity
        } else if (bm25Score >= 0.5) {
            return Quality.VERY_GOOD;  // High lexical similarity
        } else if (bm25Score >= 0.3) {
            return Quality.GOOD;       // Good lexical similarity
        } else if (bm25Score >= 0.1) {
            return Quality.AVERAGE;    // Moderate lexical similarity
        } else {
            return Quality.POOR;       // Low lexical similarity
        }
    }
    
    /**
     * Classify cosine similarity score quality (for vector/embedding search)
     * 
     * @param cosineSimilarity The cosine similarity score (0-1 range)
     * @return Quality classification
     */
    public static Quality classifyCosineSimilarity(double cosineSimilarity) {
        if (cosineSimilarity >= 0.9) {
            return Quality.EXCELLENT;  // Very high semantic similarity
        } else if (cosineSimilarity >= 0.8) {
            return Quality.VERY_GOOD;  // High semantic similarity
        } else if (cosineSimilarity >= 0.7) {
            return Quality.GOOD;       // Good semantic similarity
        } else if (cosineSimilarity >= 0.6) {
            return Quality.AVERAGE;    // Moderate semantic similarity
        } else {
            return Quality.POOR;       // Low semantic similarity
        }
    }
    
    /**
     * Get quality description for users
     */
    public static String getQualityDescription(Quality quality) {
        return switch (quality) {
            case EXCELLENT -> "Perfect match - highly relevant result";
            case VERY_GOOD -> "Very relevant result with high confidence";
            case GOOD -> "Good match - relevant and useful";
            case AVERAGE -> "Moderate relevance - may be useful depending on context";
            case POOR -> "Low relevance - consider refining your search";
        };
    }
    
    /**
     * Get quality score as percentage for display
     */
    public static int getQualityPercentage(Quality quality) {
        return switch (quality) {
            case EXCELLENT -> 95;
            case VERY_GOOD -> 85;
            case GOOD -> 75;
            case AVERAGE -> 60;
            case POOR -> 30;
        };
    }
}
