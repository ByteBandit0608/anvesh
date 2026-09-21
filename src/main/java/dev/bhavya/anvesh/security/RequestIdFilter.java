package dev.bhavya.anvesh.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts requestId into MDC so logstash encoder includes it in JSON logs.
 * WHY separate from ApiKeyAuthFilter: request id should exist even for 401 responses,
 * and MDC handling is generic.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rid = request.getHeader("X-Request-Id");
        if (rid == null || rid.isBlank()) rid = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("requestId", rid);
        MDC.put("method", request.getMethod());
        MDC.put("uri", request.getRequestURI());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
