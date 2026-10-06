package com.mstech.vitrin.gateway.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.mstech.vitrin.gateway.testing.Rules;
import com.mstech.vitrin.platform.token.Role;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RouteTableTests {
    @Test
    void pillarZeroContainsExactlyTheFourContractRoutes() {
        assertThat(RouteTable.pillarZero().rules())
                .extracting(RouteRule::id)
                .containsExactly("status", "session-create", "session-read", "session-refresh");
    }

    @ParameterizedTest
    @MethodSource("expectedRules")
    void pillarZeroRuleHasEveryContractField(RouteRule expected) {
        RouteTable table = RouteTable.pillarZero();
        RouteRule actual = table.find(expected.method(), expected.path()).orElseThrow();

        assertThat(actual.id()).isEqualTo(expected.id());
        assertThat(actual.method()).isEqualTo(expected.method());
        assertThat(actual.path()).isEqualTo(expected.path());
        assertThat(actual.upstream()).isEqualTo(expected.upstream());
        assertThat(actual.token()).isEqualTo(expected.token());
        assertThat(actual.roles()).isEqualTo(expected.roles());
        assertThat(actual.addressQuotaGroup()).isEqualTo(expected.addressQuotaGroup());
        assertThat(actual.sessionQuotaGroup()).isEqualTo(expected.sessionQuotaGroup());
        assertThat(actual.queryAllowed()).isEqualTo(expected.queryAllowed());
        assertThat(actual.bodyAllowed()).isEqualTo(expected.bodyAllowed());
    }

    @ParameterizedTest
    @MethodSource("absentRoutes")
    void lookupRequiresExactMethodAndPath(String method, String path) {
        assertThat(RouteTable.pillarZero().find(method, path)).isEmpty();
    }

    @Test
    void rejectsDuplicateIds() {
        RouteRule first = Rules.publicGet("/first");
        RouteRule second = Rules.publicGet("/second");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RouteTable(List.of(first, second)));
    }

    @Test
    void rejectsDuplicateMethodAndPath() {
        RouteRule first = Rules.publicGet("/same");
        RouteRule second =
                new RouteRule(
                        "different-id",
                        "GET",
                        "/same",
                        Upstream.LOCAL,
                        TokenRequirement.NONE,
                        Set.of(),
                        null,
                        null,
                        false,
                        false);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RouteTable(List.of(first, second)));
    }

    @Test
    void rejectsRolesOnATokenlessRule() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new RouteRule(
                                        "invalid",
                                        "GET",
                                        "/test",
                                        Upstream.LOCAL,
                                        TokenRequirement.NONE,
                                        Set.of(Role.GUEST_COMPANY),
                                        null,
                                        null,
                                        false,
                                        false));
    }

    @Test
    void rejectsSessionQuotaOnATokenlessRule() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new RouteRule(
                                        "invalid",
                                        "GET",
                                        "/test",
                                        Upstream.LOCAL,
                                        TokenRequirement.NONE,
                                        Set.of(),
                                        null,
                                        "session-read",
                                        false,
                                        false));
    }

    @Test
    void rejectsATokenRuleWithoutAllowedRoles() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new RouteRule(
                                        "invalid",
                                        "GET",
                                        "/test",
                                        Upstream.CORE,
                                        TokenRequirement.REQUIRED,
                                        Set.of(),
                                        null,
                                        null,
                                        false,
                                        false));
    }

    static Stream<RouteRule> expectedRules() {
        return Stream.of(
                new RouteRule(
                        "status",
                        "GET",
                        "/v1/public/status",
                        Upstream.LOCAL,
                        TokenRequirement.NONE,
                        Set.of(),
                        "public",
                        null,
                        false,
                        false),
                new RouteRule(
                        "session-create",
                        "POST",
                        "/v1/session",
                        Upstream.CORE,
                        TokenRequirement.NONE,
                        Set.of(),
                        "session-create",
                        null,
                        false,
                        false),
                new RouteRule(
                        "session-read",
                        "GET",
                        "/v1/session",
                        Upstream.CORE,
                        TokenRequirement.REQUIRED,
                        Set.of(Role.GUEST_COMPANY, Role.DEMO_CANDIDATE),
                        "authenticated",
                        "session-read",
                        false,
                        false),
                new RouteRule(
                        "session-refresh",
                        "POST",
                        "/v1/session/refresh",
                        Upstream.CORE,
                        TokenRequirement.NONE,
                        Set.of(),
                        "session-refresh",
                        null,
                        false,
                        false));
    }

    static Stream<Arguments> absentRoutes() {
        return Stream.of(
                Arguments.of("PUT", "/v1/session"),
                Arguments.of("get", "/v1/public/status"),
                Arguments.of("GET", "/v1/public/status/"),
                Arguments.of("GET", "/V1/public/status"),
                Arguments.of("GET", "/prefix/v1/public/status"),
                Arguments.of("GET", "/v1/public/status/suffix"),
                Arguments.of("GET", "/v1/public/status-extra"),
                Arguments.of("GET", "/v1/public"),
                Arguments.of("GET", "/internal/jwks"));
    }
}
