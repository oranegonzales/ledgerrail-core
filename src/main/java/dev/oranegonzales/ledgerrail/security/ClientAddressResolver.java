package dev.oranegonzales.ledgerrail.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class ClientAddressResolver {

    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private final PublicDemoProperties properties;

    ClientAddressResolver(PublicDemoProperties properties) {
        this.properties = properties;
    }

    String resolve(HttpServletRequest request) {
        if (properties.trustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            String trustedProxyValue = rightmostAddress(forwarded);
            if (trustedProxyValue != null) {
                return trustedProxyValue;
            }
        }
        String remoteAddress = normalizeAddress(request.getRemoteAddr());
        return remoteAddress == null ? "unknown" : remoteAddress;
    }

    private String rightmostAddress(String forwarded) {
        if (forwarded == null || forwarded.isBlank()) {
            return null;
        }
        String[] addresses = forwarded.split(",");
        return normalizeAddress(addresses[addresses.length - 1]);
    }

    private String normalizeAddress(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (!IP_LITERAL.matcher(candidate).matches()
                || (!candidate.contains(".") && !candidate.contains(":"))) {
            return null;
        }
        return candidate.toLowerCase(Locale.ROOT);
    }
}
