package com.apimarketplace.conversation.service.approval;

import com.apimarketplace.common.icon.IconSlugNormalizer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for external service display info used by the request_credential tool.
 * Maps service types (e.g., "gmail", "google-calendar", "google_calendar") to
 * their display names and icon slugs.
 *
 * Icon slugs MUST follow the canonical format from {@link IconSlugNormalizer}:
 * lowercase alphanumeric only ([a-z0-9]+), matching the SVG filenames in
 * frontend/public/icons/services/.
 */
public class ServiceInfoRegistry {

    private static final Map<String, ServiceInfo> KNOWN_SERVICES = new ConcurrentHashMap<>();

    static {
        // Email
        register("gmail", "Gmail", "gmail");
        register("outlook", "Outlook", "outlook");
        register("yahoo_mail", "Yahoo Mail", "yahoo");

        // Communication
        register("slack", "Slack", "slack");
        register("discord", "Discord", "discord");
        register("teams", "Microsoft Teams", "microsoftteams");
        register("telegram", "Telegram", "telegram");
        register("whatsapp", "WhatsApp", "whatsapp");

        // Cloud storage
        register("google_drive", "Google Drive", "googledrive");
        register("dropbox", "Dropbox", "dropbox");
        register("onedrive", "OneDrive", "onedrive");
        register("box", "Box", "box");

        // Social media
        register("twitter", "Twitter/X", "x");
        register("facebook", "Facebook", "facebook");
        register("instagram", "Instagram", "instagram");
        register("linkedin", "LinkedIn", "linkedin");
        register("tiktok", "TikTok", "tiktok");
        register("youtube", "YouTube", "youtube");

        // Productivity
        register("notion", "Notion", "notion");
        register("trello", "Trello", "trello");
        register("asana", "Asana", "asana");
        register("jira", "Jira", "jira");
        register("confluence", "Confluence", "confluence");
        register("airtable", "Airtable", "airtable");
        register("monday", "Monday.com", "monday");

        // Google Workspace
        register("google_calendar", "Google Calendar", "googlecalendar");
        register("google_sheets", "Google Sheets", "googlesheets");
        register("google_docs", "Google Docs", "googledocs");
        register("google_forms", "Google Forms", "googleforms");
        register("google_analytics", "Google Analytics", "googleanalytics");
        register("google_maps", "Google Maps", "googlemaps");
        register("google_translate", "Google Translate", "googletranslate");
        register("google_ads", "Google Ads", "googleads");
        register("google_bigquery", "Google BigQuery", "googlebigquery");
        register("google_cloud_storage", "Google Cloud Storage", "googlecloudstorage");

        // Calendar
        register("outlook_calendar", "Outlook Calendar", "outlook");

        // Development
        register("github", "GitHub", "github");
        register("gitlab", "GitLab", "gitlab");
        register("bitbucket", "Bitbucket", "bitbucket");

        // CRM
        register("salesforce", "Salesforce", "salesforce");
        register("hubspot", "HubSpot", "hubspot");
        register("pipedrive", "Pipedrive", "pipedrive");

        // Payment
        register("stripe", "Stripe", "stripe");
        register("paypal", "PayPal", "paypal");

        // E-commerce
        register("shopify", "Shopify", "shopify");

        // AI/ML
        register("openai", "OpenAI", "openai");

        // Other
        register("zapier", "Zapier", "zapier");
        register("make", "Make (Integromat)", "integromat");
        register("figma", "Figma", "figma");
    }

    /**
     * Register a service under its canonical key AND a hyphenated alias
     * (so both "google_calendar" and "google-calendar" resolve).
     */
    private static void register(String serviceType, String serviceName, String iconSlug) {
        String key = serviceType.toLowerCase();
        ServiceInfo info = new ServiceInfo(key, serviceName, null, iconSlug);
        KNOWN_SERVICES.put(key, info);
        String altKey = key.contains("_") ? key.replace('_', '-') : null;
        if (altKey != null) {
            KNOWN_SERVICES.put(altKey, info);
        }
    }

    /**
     * Get service info. Lookup is case-insensitive and handles both hyphens and underscores.
     * For unknown services, derives display name via capitalization and iconSlug via
     * {@link IconSlugNormalizer#normalize} (guaranteed [a-z0-9]+ → matches SVG filenames).
     */
    public static ServiceInfo getServiceInfo(String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return new ServiceInfo("unknown", "Unknown Service", null, null);
        }

        String normalizedType = serviceType.toLowerCase().trim();
        ServiceInfo known = KNOWN_SERVICES.get(normalizedType);
        if (known != null) {
            return known;
        }

        String derivedName = capitalizeServiceType(normalizedType);
        String derivedIconSlug = IconSlugNormalizer.normalize(normalizedType);
        return new ServiceInfo(normalizedType, derivedName, null, derivedIconSlug);
    }

    public static boolean isKnownService(String serviceType) {
        if (serviceType == null) return false;
        return KNOWN_SERVICES.containsKey(serviceType.toLowerCase().trim());
    }

    /**
     * Capitalize a service type for display. Splits on underscores and hyphens.
     */
    private static String capitalizeServiceType(String serviceType) {
        if (serviceType == null || serviceType.isEmpty()) {
            return "Unknown";
        }
        String[] parts = serviceType.split("[_\\-]");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (result.length() > 0) result.append(" ");
                result.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) result.append(part.substring(1).toLowerCase());
            }
        }
        return result.toString();
    }
}
