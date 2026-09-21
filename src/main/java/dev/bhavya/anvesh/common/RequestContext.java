package dev.bhavya.anvesh.common;

/**
 * Holds per-request tenant info set by ApiKeyAuthFilter.
 * WHY ThreadLocal not SecurityContext: we don't use Spring Security's full filter chain
 * (to keep tests simple). A ThreadLocal is enough because each request runs on one thread,
 * and we clear it in the filter's finally block. The ingest pool copies owner_id explicitly
 * via method args, not via this ThreadLocal, so async indexing doesn't inherit a stale value.
 */
public final class RequestContext {
    private static final ThreadLocal<String> OWNER = new ThreadLocal<>();
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> API_KEY_ID = new ThreadLocal<>();

    private RequestContext() {}

    public static void set(String ownerId, String requestId, String apiKeyId) {
        OWNER.set(ownerId);
        REQUEST_ID.set(requestId);
        API_KEY_ID.set(apiKeyId);
    }

    public static String ownerId() {
        String o = OWNER.get();
        return o != null ? o : "public";
    }

    public static String requestId() {
        return REQUEST_ID.get();
    }

    public static String apiKeyId() {
        return API_KEY_ID.get();
    }

    public static void clear() {
        OWNER.remove();
        REQUEST_ID.remove();
        API_KEY_ID.remove();
    }
}
