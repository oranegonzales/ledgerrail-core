package dev.oranegonzales.ledgerrail.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class PortfolioApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Portfolio-Key";
    private static final Set<String> PUBLIC_ACTUATOR_ROUTES = Set.of(
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/info");
    private static final Pattern PUBLIC_TRANSFER_ITEM = Pattern.compile(
            "^/api/v1/transfers/[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-"
                    + "[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?:/ledger-entries)?$");
    static final String PUBLIC_DEMO_REQUEST = PortfolioApiKeyFilter.class.getName() + ".publicDemoRequest";
    private final byte[] expectedApiKey;
    private final PublicDemoProperties publicDemoProperties;

    public PortfolioApiKeyFilter(
            @Value("${ledgerrail.security.api-key}") String apiKey,
            PublicDemoProperties publicDemoProperties) {
        if (apiKey.length() < 32 || apiKey.length() > 256) {
            throw new IllegalStateException(
                    "ledgerrail.security.api-key must contain between 32 and 256 characters");
        }
        this.expectedApiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.publicDemoProperties = publicDemoProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") && !path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        if (supplied != null && !supplied.isBlank()) {
            byte[] suppliedBytes = supplied.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expectedApiKey, suppliedBytes)) {
                unauthorized(response, "The supplied X-Portfolio-Key is invalid.");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        if ("GET".equals(request.getMethod()) && PUBLIC_ACTUATOR_ROUTES.contains(request.getRequestURI())) {
            request.setAttribute(PUBLIC_DEMO_REQUEST, Boolean.TRUE);
            filterChain.doFilter(request, response);
            return;
        }

        if (publicDemoProperties.enabled() && isPublicDemoRoute(request)) {
            request.setAttribute(PUBLIC_DEMO_REQUEST, Boolean.TRUE);
            filterChain.doFilter(request, response);
            return;
        }

        unauthorized(response, "A valid X-Portfolio-Key header is required for operator endpoints.");
    }

    private boolean isPublicDemoRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("POST".equals(request.getMethod())) {
            return path.equals("/api/v1/transfers");
        }
        if ("GET".equals(request.getMethod())) {
            return path.equals("/api/v1/transfers") || PUBLIC_TRANSFER_ITEM.matcher(path).matches();
        }
        return false;
    }

    private void unauthorized(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"%s\"}"
                .formatted(detail));
    }
}
