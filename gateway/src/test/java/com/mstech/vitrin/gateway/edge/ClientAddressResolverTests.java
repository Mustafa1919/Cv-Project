package com.mstech.vitrin.gateway.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.testing.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressResolverTests {
    private final ClientAddressResolver resolver =
            new ClientAddressResolver(Rules.gatewayProperties("CF-Connecting-IP"));

    @Test
    void singleValidConfiguredHeaderWins() {
        MockHttpServletRequest request = request();
        request.addHeader("CF-Connecting-IP", "203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo(new ClientAddress("203.0.113.7", false));
    }

    @Test
    void usesTheConfiguredHeaderName() {
        ClientAddressResolver custom =
                new ClientAddressResolver(Rules.gatewayProperties("Client-IP"));
        MockHttpServletRequest request = request();
        request.addHeader("Client-IP", "203.0.113.7");
        request.addHeader("CF-Connecting-IP", "203.0.113.8");

        assertThat(custom.resolve(request)).isEqualTo(new ClientAddress("203.0.113.7", false));
    }

    @Test
    void twoConfiguredHeaderValuesFallBackToRemoteAddress() {
        MockHttpServletRequest request = request();
        request.addHeader("CF-Connecting-IP", "203.0.113.7");
        request.addHeader("CF-Connecting-IP", "203.0.113.8");

        assertThat(resolver.resolve(request)).isEqualTo(new ClientAddress("192.0.2.10", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.2.3.4, 5.6.7.8", "fe80::1%eth0"})
    void invalidConfiguredHeaderFallsBackToRemoteAddress(String value) {
        MockHttpServletRequest request = request();
        request.addHeader("CF-Connecting-IP", value);

        assertThat(resolver.resolve(request)).isEqualTo(new ClientAddress("192.0.2.10", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Forwarded-For", "Forwarded", "X-Real-IP", "True-Client-IP"})
    void neverConsultsOtherAddressHeaders(String header) {
        MockHttpServletRequest request = request();
        request.addHeader(header, "203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo(new ClientAddress("192.0.2.10", false));
    }

    @Test
    void addressesWithinOneIpv6PrefixShareTheCanonicalAddress() {
        ClientAddress first = resolve("2001:db8:1234:5678::1");
        ClientAddress second = resolve("2001:db8:1234:5678:ffff:abcd:1234:5678");

        assertThat(first.canonical()).isEqualTo(second.canonical());
        assertThat(first.ipv6()).isTrue();
        assertThat(second.ipv6()).isTrue();
    }

    @Test
    void differentIpv6PrefixesRemainDistinct() {
        ClientAddress first = resolve("2001:db8:1234:5678::1");
        ClientAddress second = resolve("2001:db8:1234:5679::1");

        assertThat(first.canonical()).isNotEqualTo(second.canonical());
    }

    @Test
    void mappedIpv6IsTheSameAsPlainIpv4() {
        ClientAddress mapped = resolve("::ffff:203.0.113.7");
        ClientAddress plain = resolve("203.0.113.7");

        assertThat(mapped).isEqualTo(plain);
        assertThat(mapped).isEqualTo(new ClientAddress("203.0.113.7", false));
    }

    @Test
    void invalidRemoteAddressBecomesUnknown() {
        MockHttpServletRequest request = request();
        request.setRemoteAddr("not-an-address");

        assertThat(resolver.resolve(request)).isEqualTo(new ClientAddress("unknown", false));
    }

    private ClientAddress resolve(String literal) {
        MockHttpServletRequest request = request();
        request.addHeader("CF-Connecting-IP", literal);
        return resolver.resolve(request);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        return request;
    }
}
