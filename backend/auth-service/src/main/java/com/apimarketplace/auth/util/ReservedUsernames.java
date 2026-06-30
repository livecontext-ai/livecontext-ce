package com.apimarketplace.auth.util;

import java.util.Set;
import java.util.HashSet;

/**
 * Utility class for managing reserved usernames
 */
public class ReservedUsernames {
    
    // List of reserved usernames (case-insensitive)
    private static final Set<String> RESERVED_USERNAMES = new HashSet<>();
    
    static {
        // System names
        RESERVED_USERNAMES.add("system");
        RESERVED_USERNAMES.add("admin");
        RESERVED_USERNAMES.add("administrator");
        RESERVED_USERNAMES.add("root");
        RESERVED_USERNAMES.add("superuser");
        RESERVED_USERNAMES.add("super");
        
        // Technical names
        RESERVED_USERNAMES.add("api");
        RESERVED_USERNAMES.add("app");
        RESERVED_USERNAMES.add("service");
        RESERVED_USERNAMES.add("bot");
        RESERVED_USERNAMES.add("support");
        RESERVED_USERNAMES.add("help");
        RESERVED_USERNAMES.add("info");
        RESERVED_USERNAMES.add("contact");
        RESERVED_USERNAMES.add("noreply");
        RESERVED_USERNAMES.add("no-reply");
        
        // Role names
        RESERVED_USERNAMES.add("user");
        RESERVED_USERNAMES.add("guest");
        RESERVED_USERNAMES.add("moderator");
        RESERVED_USERNAMES.add("mod");
        RESERVED_USERNAMES.add("owner");
        RESERVED_USERNAMES.add("manager");
        
        // Page/route names
        RESERVED_USERNAMES.add("dashboard");
        RESERVED_USERNAMES.add("profile");
        RESERVED_USERNAMES.add("settings");
        RESERVED_USERNAMES.add("login");
        RESERVED_USERNAMES.add("logout");
        RESERVED_USERNAMES.add("register");
        RESERVED_USERNAMES.add("signup");
        RESERVED_USERNAMES.add("signin");
        RESERVED_USERNAMES.add("home");
        RESERVED_USERNAMES.add("about");
        RESERVED_USERNAMES.add("contact");
        RESERVED_USERNAMES.add("privacy");
        RESERVED_USERNAMES.add("terms");
        RESERVED_USERNAMES.add("faq");
        
        // Feature names
        RESERVED_USERNAMES.add("search");
        RESERVED_USERNAMES.add("browse");
        RESERVED_USERNAMES.add("explore");
        RESERVED_USERNAMES.add("discover");
        RESERVED_USERNAMES.add("create");
        RESERVED_USERNAMES.add("edit");
        RESERVED_USERNAMES.add("delete");
        RESERVED_USERNAMES.add("update");
        RESERVED_USERNAMES.add("upload");
        RESERVED_USERNAMES.add("download");
        
        // Status names
        RESERVED_USERNAMES.add("active");
        RESERVED_USERNAMES.add("inactive");
        RESERVED_USERNAMES.add("pending");
        RESERVED_USERNAMES.add("approved");
        RESERVED_USERNAMES.add("rejected");
        RESERVED_USERNAMES.add("banned");
        RESERVED_USERNAMES.add("suspended");
        
        // Test names
        RESERVED_USERNAMES.add("test");
        RESERVED_USERNAMES.add("testing");
        RESERVED_USERNAMES.add("demo");
        RESERVED_USERNAMES.add("sample");
        RESERVED_USERNAMES.add("example");
        
        // Development names
        RESERVED_USERNAMES.add("dev");
        RESERVED_USERNAMES.add("development");
        RESERVED_USERNAMES.add("staging");
        RESERVED_USERNAMES.add("production");
        RESERVED_USERNAMES.add("prod");
        
        // Security names
        RESERVED_USERNAMES.add("security");
        RESERVED_USERNAMES.add("auth");
        RESERVED_USERNAMES.add("authentication");
        RESERVED_USERNAMES.add("authorization");
        RESERVED_USERNAMES.add("session");
        RESERVED_USERNAMES.add("token");
        RESERVED_USERNAMES.add("password");
        RESERVED_USERNAMES.add("reset");
        
        // Database names
        RESERVED_USERNAMES.add("database");
        RESERVED_USERNAMES.add("db");
        RESERVED_USERNAMES.add("data");
        RESERVED_USERNAMES.add("storage");
        RESERVED_USERNAMES.add("backup");
        
        // Communication names
        RESERVED_USERNAMES.add("mail");
        RESERVED_USERNAMES.add("email");
        RESERVED_USERNAMES.add("notification");
        RESERVED_USERNAMES.add("message");
        RESERVED_USERNAMES.add("chat");
        RESERVED_USERNAMES.add("forum");
        
        // Commerce names
        RESERVED_USERNAMES.add("shop");
        RESERVED_USERNAMES.add("store");
        RESERVED_USERNAMES.add("marketplace");
        RESERVED_USERNAMES.add("payment");
        RESERVED_USERNAMES.add("billing");
        RESERVED_USERNAMES.add("invoice");
        RESERVED_USERNAMES.add("order");
        RESERVED_USERNAMES.add("cart");
        RESERVED_USERNAMES.add("checkout");
        
        // Content names
        RESERVED_USERNAMES.add("content");
        RESERVED_USERNAMES.add("media");
        RESERVED_USERNAMES.add("image");
        RESERVED_USERNAMES.add("video");
        RESERVED_USERNAMES.add("audio");
        RESERVED_USERNAMES.add("file");
        RESERVED_USERNAMES.add("document");
        
        // Configuration names
        RESERVED_USERNAMES.add("config");
        RESERVED_USERNAMES.add("configuration");
        RESERVED_USERNAMES.add("setup");
        RESERVED_USERNAMES.add("install");
        RESERVED_USERNAMES.add("uninstall");
        
        // Monitoring names
        RESERVED_USERNAMES.add("monitor");
        RESERVED_USERNAMES.add("log");
        RESERVED_USERNAMES.add("logs");
        RESERVED_USERNAMES.add("analytics");
        RESERVED_USERNAMES.add("metrics");
        RESERVED_USERNAMES.add("stats");
        RESERVED_USERNAMES.add("statistics");
    }
    
    /**
     * Checks if a username is reserved
     * @param username The username to check
     * @return true if the name is reserved, false otherwise
     */
    public static boolean isReserved(String username) {
        if (username == null || username.trim().isEmpty()) {
            return false;
        }
        return RESERVED_USERNAMES.contains(username.toLowerCase().trim());
    }
    
    /**
     * Returns the list of reserved usernames (read-only)
     * @return Set of reserved names
     */
    public static Set<String> getReservedUsernames() {
        return new HashSet<>(RESERVED_USERNAMES);
    }
    
    /**
     * Returns the number of reserved usernames
     * @return The number of reserved names
     */
    public static int getReservedUsernamesCount() {
        return RESERVED_USERNAMES.size();
    }
}
