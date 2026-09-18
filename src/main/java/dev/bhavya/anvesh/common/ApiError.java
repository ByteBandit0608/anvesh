package dev.bhavya.anvesh.common;

import java.time.Instant;
import java.util.Map;

/** Uniform error body — every non-2xx response looks like this. */
public record ApiError(int status, String error, String message, String path, Instant timestamp, Map<String, String> details) {
    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(status, error, message, path, Instant.now(), Map.of());
    }
}
