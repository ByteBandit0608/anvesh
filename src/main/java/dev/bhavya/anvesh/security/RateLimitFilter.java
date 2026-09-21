package dev.bhavya.anvesh.security;

import dev.bhavya.anvesh.common.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * After ApiKeyAuthFilter, enforce per-owner rate limit.
 * Returns 429 with Retry-After when exhausted.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimit;

    public RateLimitFilter(RateLimitService rateLimit) { this.rateLimit = rateLimit; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        // Don't rate-limit health/docs
        if (path.startsWith("/actuator/health") || path.startsWith("/docs") || path.startsWith("/v3/api-docs")) {
            chain.doFilter(request, response);
            return;
        }
        String owner = RequestContext.ownerId();
        if (!rateLimit.tryConsume(owner)) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Rate limit exceeded\",\"retryAfter\":60}");
            return;
        }
        chain.doFilter(request, response);
    }
}
