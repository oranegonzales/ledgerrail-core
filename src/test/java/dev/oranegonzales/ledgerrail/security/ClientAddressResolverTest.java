package dev.oranegonzales.ledgerrail.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressResolverTest {

    @Test
    void usesRightmostAddressAppendedByTrustedProxy() {
        ClientAddressResolver resolver = resolver(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.4");
        request.addHeader("X-Forwarded-For", "198.51.100.99, 203.0.113.25");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.25");
    }

    @Test
    void ignoresForwardedHeaderUnlessProxyTrustIsExplicitlyEnabled() {
        ClientAddressResolver resolver = resolver(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.99");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void fallsBackToSocketAddressForMalformedProxyValue() {
        ClientAddressResolver resolver = resolver(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "attacker-controlled-hostname");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    private ClientAddressResolver resolver(boolean trustForwardedFor) {
        return new ClientAddressResolver(new PublicDemoProperties(true, 60, 500, trustForwardedFor));
    }
}
