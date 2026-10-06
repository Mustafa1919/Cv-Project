package com.mstech.vitrin.gateway.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SitePropertiesTests {
    @ParameterizedTest
    @ValueSource(strings = {"https://example.test", "http://localhost:4321"})
    void acceptsSerializedOrigins(String origin) {
        assertThat(new SiteProperties(origin).origin()).isEqualTo(origin);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://example.test/",
                "https://example.test/path",
                "https://EXAMPLE.test",
                "HTTPS://example.test",
                "https://user@example.test",
                "https://example.test?query=1",
                "https://example.test#fragment",
                "ftp://example.test",
                "example.test",
                "null",
                "",
                " "
            })
    void rejectsValuesThatAreNotExactSerializedOrigins(String origin) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SiteProperties(origin))
                .withMessage("Invalid vitrin.site.origin");
    }
}
