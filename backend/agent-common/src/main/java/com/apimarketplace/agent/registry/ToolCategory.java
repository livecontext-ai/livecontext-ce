package com.apimarketplace.agent.registry;

/**
 * Categories of agent tools.
 * Each category groups related tools for easier discovery.
 */
public enum ToolCategory {

    SEARCH("search", "Search and discovery tools", "Find API tools by query"),
    WORKFLOW("workflow", "Workflow management tools", "Create, read, update, delete and execute workflows"),
    AGENT("agent", "AI Agent configuration tools", "Manage AI agent configurations"),
    INTERFACE("interface", "Interface management tools", "Create visual interfaces: display workflow data OR build interactive apps with action_mapping + triggers"),
    DATASOURCE("datasource", "Data source management tools", "Manage data sources for workflows"),
    VISUALIZATION("visualization", "Visualization tools", "Display workflows, datasources, and interfaces in the chat"),
    CATALOG("catalog", "Catalog and schema tools", "Discover API tools and their response schemas for SpEL mapping"),
    HELP("help", "Documentation and help tools", "Get help, schemas, and examples"),
    UTILITY("utility", "Utility tools", "File operations, data transformation, and other utilities"),
    APPLICATION("application", "Application management tools", "Browse, acquire, and display marketplace applications"),
    WEB_SEARCH("websearch", "Web search and fetching tools", "Search the web and extract content from pages"),
    IMAGE_GENERATION("imagegeneration", "Image generation tools", "Generate images from text prompts via OpenAI gpt-image-1.5/mini or Google Gemini 2.5 Flash Image / 3 Pro Image");

    private final String slug;
    private final String displayName;
    private final String description;

    ToolCategory(String slug, String displayName, String description) {
        this.slug = slug;
        this.displayName = displayName;
        this.description = description;
    }

    public String getSlug() {
        return slug;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Find category by slug (case-insensitive).
     */
    public static ToolCategory fromSlug(String slug) {
        if (slug == null) return null;
        for (ToolCategory category : values()) {
            if (category.slug.equalsIgnoreCase(slug)) {
                return category;
            }
        }
        return null;
    }
}
