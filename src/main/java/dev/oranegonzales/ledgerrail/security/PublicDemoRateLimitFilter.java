package dev.oranegonzales.ledgerrail.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class PublicDemoRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_CLIENTS = 10_000;
    private final PublicDemoProperties properties;
    private final DemoUsageRepository usageRepository;
    private final ClientAddressResolver clientAddressResolver;
    private final Clock clock;
    private final Map<String, RequestWindow> clientWindows = new HashMap<>();
    private final Counter acceptedCounter;
    private final Counter minuteLimitCounter;
    private final Counter dailyLimitCounter;

    PublicDemoRateLimitFilter(
            PublicDemoProperties properties,
            DemoUsageRepository usageRepository,
            ClientAddressResolver clientAddressResolver,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.usageRepository = usageRepository;
        this.clientAddressResolver = clientAddressResolver;
        this.clock = clock;
        this.acceptedCounter = meterRegistry.counter("ledgerrail.demo.requests", "outcome", "accepted");
        this.minuteLimitCounter = meterRegistry.counter("ledgerrail.demo.requests", "outcome", "minute_limited");
        this.dailyLimitCounter = meterRegistry.counter("ledgerrail.demo.requests", "outcome", "daily_limited");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !Boolean.TRUE.equals(request.getAttribute(PortfolioApiKeyFilter.PUBLIC_DEMO_REQUEST));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Instant now = clock.instant();
        if (!acquireMinutePermit(clientAddressResolver.resolve(request), now)) {
            minuteLimitCounter.increment();
            reject(response, 60, "The public demo request limit was reached. Try again in a minute.");
            return;
        }
        boolean transferWrite = isTransferWrite(request);
        LocalDate usageDate = now.atZone(ZoneOffset.UTC).toLocalDate();
        if (transferWrite
                && !usageRepository.tryAcquireWrite(usageDate, properties.writesPerDay(), now)) {
            dailyLimitCounter.increment();
            long retryAfter = Math.max(1, now.atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toEpochSecond() - now.getEpochSecond());
            reject(response, retryAfter, "The public demo's daily write quota was reached. Try again tomorrow UTC.");
            return;
        }
        acceptedCounter.increment();
        response.setHeader("X-RateLimit-Policy",
                "%d requests/minute; %d writes/day".formatted(
                        properties.requestsPerMinute(), properties.writesPerDay()));
        boolean completed = false;
        try {
            filterChain.doFilter(request, response);
            completed = true;
        } finally {
            if (transferWrite && (!completed || response.getStatus() >= 400)) {
                usageRepository.releaseWrite(usageDate, clock.instant());
            }
        }
    }

    private boolean isTransferWrite(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && "/api/v1/transfers".equals(request.getRequestURI());
    }

    private synchronized boolean acquireMinutePermit(String clientKey, Instant now) {
        long minute = now.getEpochSecond() / 60;
        if (clientWindows.size() >= MAX_TRACKED_CLIENTS) {
            Iterator<RequestWindow> iterator = clientWindows.values().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().minute() < minute) {
                    iterator.remove();
                }
            }
            if (clientWindows.size() >= MAX_TRACKED_CLIENTS && !clientWindows.containsKey(clientKey)) {
                return false;
            }
        }
        RequestWindow current = clientWindows.get(clientKey);
        if (current == null || current.minute() != minute) {
            clientWindows.put(clientKey, new RequestWindow(minute, 1));
            return true;
        }
        if (current.count() >= properties.requestsPerMinute()) {
            return false;
        }
        clientWindows.put(clientKey, new RequestWindow(minute, current.count() + 1));
        return true;
    }

    private void reject(HttpServletResponse response, long retryAfter, String detail) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"title\":\"Public demo limit reached\",\"status\":429,\"detail\":\"%s\"}"
                .formatted(detail));
    }

    private record RequestWindow(long minute, int count) {
    }
}
