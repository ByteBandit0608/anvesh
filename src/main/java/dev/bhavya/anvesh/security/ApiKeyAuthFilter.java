package dev.bhavya.anvesh.security;

import dev.bhavya.anvesh.common.RequestContext;
import dev.bhavya.anvesh.config.AnveshProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Reads X-API-Key, validates against api_keys table, sets RequestContext.
 * WHY not Spring Security: we want tests to run without any security config, and we want
 * owner_id available as a simple ThreadLocal for JDBC code without pulling in SecurityContext.
 * If requireApiKey=false (default for dev/test), missing header -> owner=anonymousOwner ("public").
 * If true, missing header -> 401.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final String HEADER = "X-API-Key";

    private final ApiKeyRepository repo;
    private final AnveshProperties props;

    public ApiKeyAuthFilter(ApiKeyRepository repo, AnveshProperties props) {
        this.repo = repo;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        response.setHeader("X-Request-Id", requestId);
        String rawKey = request.getHeader(HEADER);
        String ownerId;
        String apiKeyId = null;

        try {
            if (rawKey != null && !rawKey.isBlank()) {
                String hash = ApiKeyService.sha256Hex(rawKey.trim());
                var key = repo.findByHash(hash);
                if (key.isEmpty()) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Invalid API key\"}");
                    return;
                }
                ownerId = key.get().ownerId();
                apiKeyId = key.get().id().toString();
                // Touch last_used async to avoid slowing request; for now sync (cheap).
                try { repo.touchLastUsed(key.get().id()); } catch (Exception e) { log.warn("touchLastUsed failed", e); }
            } else {
                if (props.security() != null && props.security().requireApiKey()) {
                    // Allow health, docs, swagger without key even when required
                    String path = request.getRequestURI();
                    if (path.startsWith("/actuator/health") || path.startsWith("/docs") || path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui")) {
                        ownerId = props.security().anonymousOwner();
                    } else {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\":\"Missing X-API-Key header\"}");
                        return;
                    }
                } else {
                    ownerId = props.security() != null ? props.security().anonymousOwner() : "public";
                }
            }
            RequestContext.set(ownerId, requestId, apiKeyId);
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }
}
