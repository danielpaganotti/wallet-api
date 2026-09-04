package com.walletapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletapi.common.ApiError;
import com.walletapi.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Lightweight API key gateway. The challenge scope explicitly excludes end-user
 * authentication (JWT/OAuth), so a shared-secret header is used instead to
 * protect the service-to-service endpoints, as suggested in section 5.2.
 */
@Component
@Order(1)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private static final List<String> PUBLIC_PATHS = List.of(
            "/swagger-ui", "/v3/api-docs", "/actuator/health", "/actuator/info"
    );

    private final String expectedApiKey;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public ApiKeyAuthFilter(@Value("${api.security.api-key}") String expectedApiKey) {
        this.expectedApiKey = expectedApiKey;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String providedKey = request.getHeader(API_KEY_HEADER);
        if (providedKey == null || !providedKey.equals(expectedApiKey)) {
            writeUnauthorized(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError error = ApiError.of(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                ErrorCode.UNAUTHORIZED,
                "Missing or invalid API key. Provide a valid '" + API_KEY_HEADER + "' header.",
                request.getRequestURI()
        );
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
