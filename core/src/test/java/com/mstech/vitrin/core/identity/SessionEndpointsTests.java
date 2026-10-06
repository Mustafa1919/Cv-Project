package com.mstech.vitrin.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.platform.token.Role;
import com.mstech.vitrin.platform.token.TokenKind;
import com.mstech.vitrin.platform.token.TokenVerifier;
import com.mstech.vitrin.platform.token.VerifiedToken;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "vitrin.identity.issuer=vitrin-core",
            "vitrin.identity.audience=vitrin-api",
            "vitrin.identity.access-token-ttl=15m",
            "vitrin.identity.guest-session-ttl=24h"
        })
@ActiveProfiles("test")
@Import(SessionEndpointsTests.ClockConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class SessionEndpointsTests {
    private static final Instant NOW = Instant.parse("2026-10-05T11:00:00Z");
    private static final Pattern COOKIE_SEPARATOR = Pattern.compile(";\\s*");

    private final int serverPort;
    private final MutableClock clock;
    private final JsonMapper mapper;
    private final SigningKeys keys;
    private final IdentityProperties properties;

    @Autowired
    SessionEndpointsTests(
            @Value("${local.server.port}") int serverPort,
            MutableClock clock,
            JsonMapper mapper,
            SigningKeys keys,
            IdentityProperties properties) {
        this.serverPort = serverPort;
        this.clock = clock;
        this.mapper = mapper;
        this.keys = keys;
        this.properties = properties;
    }

    @BeforeEach
    void resetClock() {
        clock.set(NOW);
    }

    @Test
    void createsGuestSessionWithExactBodyAndCookieAttributes() throws Exception {
        HttpResponse<String> response = send("POST", "/v1/session", null);

        assertThat(response.statusCode()).isEqualTo(201);
        assertNoStore(response);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .isEqualTo("application/json");
        JsonNode body = json(response);
        assertThat(body.size()).isEqualTo(3);
        assertThat(text(body, "role")).isEqualTo("guest_company");
        assertThat(text(body, "sessionShortId")).matches("[0-9a-f]{4}…[0-9a-f]{4}");
        assertThat(text(body, "accessExpiresAt")).isEqualTo(NOW.plusSeconds(900).toString());
        assertThat(response.headers().allValues("Set-Cookie")).hasSize(2);

        ParsedCookie access = cookie(response, IdentityCookies.ACCESS_NAME);
        ParsedCookie refresh = cookie(response, IdentityCookies.REFRESH_NAME);
        assertCookieAttributes(access, "__Host-vitrin_at", "/", 900);
        assertCookieAttributes(refresh, "__Secure-vitrin_rt", "/v1/session/refresh", 86400);

        TokenVerifier verifier =
                new TokenVerifier(properties.issuer(), properties.audience(), clock, keys);
        VerifiedToken verifiedAccess = verifier.verify(access.value(), TokenKind.ACCESS);
        VerifiedToken verifiedRefresh = verifier.verify(refresh.value(), TokenKind.REFRESH);
        assertThat(verifiedAccess.sessionId()).matches("[0-9a-f]{32}");
        assertThat(verifiedRefresh.sessionId()).isEqualTo(verifiedAccess.sessionId());
        assertThat(verifiedRefresh.expiresAt()).isEqualTo(NOW.plusSeconds(86400));
        assertThat(text(body, "sessionShortId"))
                .isEqualTo(
                        new RequestIdentity(
                                        verifiedAccess.sessionId(),
                                        verifiedAccess.role(),
                                        verifiedAccess.expiresAt())
                                .shortSessionId());
        assertThat(response.body()).doesNotContain(verifiedAccess.sessionId());
    }

    @Test
    void existingAccessCredentialReturnsSameSessionWithoutSettingCookies() throws Exception {
        HttpResponse<String> created = create();
        ParsedCookie access = cookie(created, IdentityCookies.ACCESS_NAME);

        HttpResponse<String> response = send("POST", "/v1/session", access.header());

        assertThat(response.statusCode()).isEqualTo(200);
        assertNoStore(response);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(json(response)).isEqualTo(json(created));
    }

    @Test
    void tamperedAccessCredentialOnCreationStartsDifferentSession() throws Exception {
        HttpResponse<String> created = create();
        ParsedCookie access = cookie(created, IdentityCookies.ACCESS_NAME);

        HttpResponse<String> response =
                send(
                        "POST",
                        "/v1/session",
                        credential(IdentityCookies.ACCESS_NAME, tamperSignature(access.value())));

        assertThat(response.statusCode()).isEqualTo(201);
        assertNoStore(response);
        assertThat(response.headers().allValues("Set-Cookie")).hasSize(2);
        assertThat(text(json(response), "sessionShortId"))
                .isNotEqualTo(text(json(created), "sessionShortId"));
    }

    @Test
    void creationRejectsDeclaredContentLengthBody() throws Exception {
        HttpResponse<String> response =
                send(
                        "POST",
                        "/v1/session",
                        null,
                        HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8));

        assertProblem(response, 400, "BODY_NOT_ALLOWED");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void creationRejectsChunkedBody() throws Exception {
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> response =
                send(
                        "POST",
                        "/v1/session",
                        null,
                        HttpRequest.BodyPublishers.ofInputStream(
                                () -> new ByteArrayInputStream(bytes)));

        assertProblem(response, 400, "BODY_NOT_ALLOWED");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void refreshRejectsDeclaredBody() throws Exception {
        HttpResponse<String> created = create();
        ParsedCookie refresh = cookie(created, IdentityCookies.REFRESH_NAME);

        HttpResponse<String> response =
                send(
                        "POST",
                        "/v1/session/refresh",
                        refresh.header(),
                        HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8));

        assertProblem(response, 400, "BODY_NOT_ALLOWED");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void currentSessionRequiresAccessCredential() throws Exception {
        HttpResponse<String> response = send("GET", "/v1/session", null);

        assertProblem(response, 401, "ACCESS_TOKEN_MISSING");
    }

    @Test
    void currentSessionReturnsBoundIdentity() throws Exception {
        HttpResponse<String> created = create();
        ParsedCookie access = cookie(created, IdentityCookies.ACCESS_NAME);

        HttpResponse<String> response = send("GET", "/v1/session", access.header());

        assertThat(response.statusCode()).isEqualTo(200);
        assertNoStore(response);
        assertThat(json(response)).isEqualTo(json(created));
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void directCoreRequestRejectsTamperedSignature() throws Exception {
        ParsedCookie access = cookie(create(), IdentityCookies.ACCESS_NAME);

        HttpResponse<String> response =
                send(
                        "GET",
                        "/v1/session",
                        credential(IdentityCookies.ACCESS_NAME, tamperSignature(access.value())));

        assertProblem(response, 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void directCoreRequestRejectsAccessAtExactExpiration() throws Exception {
        ParsedCookie access = cookie(create(), IdentityCookies.ACCESS_NAME);
        clock.set(NOW.plusSeconds(900));

        HttpResponse<String> response = send("GET", "/v1/session", access.header());

        assertProblem(response, 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void accessIsValidOneSecondBeforeExpiration() throws Exception {
        ParsedCookie access = cookie(create(), IdentityCookies.ACCESS_NAME);
        clock.set(NOW.plusSeconds(899));

        HttpResponse<String> response = send("GET", "/v1/session", access.header());

        assertThat(response.statusCode()).isEqualTo(200);
        assertNoStore(response);
    }

    @Test
    void directCoreRequestRejectsRoleChangedWithoutResigning() throws Exception {
        ParsedCookie access = cookie(create(), IdentityCookies.ACCESS_NAME);

        HttpResponse<String> response =
                send(
                        "GET",
                        "/v1/session",
                        credential(
                                IdentityCookies.ACCESS_NAME,
                                changeRoleWithoutResigning(
                                        access.value(), Role.DEMO_CANDIDATE.claimValue())));

        assertProblem(response, 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void directCoreRequestRejectsCorrectlySignedUnknownRole() throws Exception {
        HttpResponse<String> response =
                send(
                        "GET",
                        "/v1/session",
                        credential(IdentityCookies.ACCESS_NAME, signAccessWithRole("owner")));

        assertProblem(response, 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void refreshCredentialCannotBeUsedAsAccessCredential() throws Exception {
        ParsedCookie refresh = cookie(create(), IdentityCookies.REFRESH_NAME);

        HttpResponse<String> response =
                send(
                        "GET",
                        "/v1/session",
                        credential(IdentityCookies.ACCESS_NAME, refresh.value()));

        assertProblem(response, 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void refreshRequiresRefreshCredential() throws Exception {
        HttpResponse<String> response = send("POST", "/v1/session/refresh", null);

        assertProblem(response, 401, "REFRESH_TOKEN_MISSING");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void accessCredentialCannotBeUsedAsRefreshCredential() throws Exception {
        ParsedCookie access = cookie(create(), IdentityCookies.ACCESS_NAME);

        HttpResponse<String> response =
                send(
                        "POST",
                        "/v1/session/refresh",
                        credential(IdentityCookies.REFRESH_NAME, access.value()));

        assertProblem(response, 401, "REFRESH_TOKEN_INVALID");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void validRefreshSetsOnlyAccessCookieAndKeepsSession() throws Exception {
        HttpResponse<String> created = create();
        ParsedCookie refresh = cookie(created, IdentityCookies.REFRESH_NAME);
        clock.advance(Duration.ofSeconds(60));

        HttpResponse<String> response = send("POST", "/v1/session/refresh", refresh.header());

        assertThat(response.statusCode()).isEqualTo(200);
        assertNoStore(response);
        assertThat(response.headers().allValues("Set-Cookie")).hasSize(1);
        ParsedCookie access = cookie(response, IdentityCookies.ACCESS_NAME);
        assertCookieAttributes(access, "__Host-vitrin_at", "/", 900);
        assertThat(text(json(response), "sessionShortId"))
                .isEqualTo(text(json(created), "sessionShortId"));
        assertThat(text(json(response), "role")).isEqualTo("guest_company");
        assertThat(text(json(response), "accessExpiresAt"))
                .isEqualTo(NOW.plusSeconds(960).toString());
    }

    @Test
    void refreshStillWorksAfterOldAccessExpires() throws Exception {
        HttpResponse<String> created = create();
        ParsedCookie oldAccess = cookie(created, IdentityCookies.ACCESS_NAME);
        ParsedCookie refresh = cookie(created, IdentityCookies.REFRESH_NAME);
        clock.advance(Duration.ofSeconds(901));

        HttpResponse<String> denied = send("GET", "/v1/session", oldAccess.header());
        assertProblem(denied, 401, "ACCESS_TOKEN_INVALID");

        HttpResponse<String> refreshed = send("POST", "/v1/session/refresh", refresh.header());
        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertNoStore(refreshed);
        assertThat(refreshed.headers().allValues("Set-Cookie")).hasSize(1);
        assertThat(text(json(refreshed), "sessionShortId"))
                .isEqualTo(text(json(created), "sessionShortId"));

        ParsedCookie newAccess = cookie(refreshed, IdentityCookies.ACCESS_NAME);
        HttpResponse<String> current = send("GET", "/v1/session", newAccess.header());
        assertThat(current.statusCode()).isEqualTo(200);
        assertNoStore(current);
    }

    @Test
    void refreshFailsAfterSessionExpires() throws Exception {
        ParsedCookie refresh = cookie(create(), IdentityCookies.REFRESH_NAME);
        clock.advance(Duration.ofSeconds(86401));

        HttpResponse<String> response = send("POST", "/v1/session/refresh", refresh.header());

        assertProblem(response, 401, "REFRESH_TOKEN_INVALID");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void refreshFailsExactlyAtSessionExpiration() throws Exception {
        ParsedCookie refresh = cookie(create(), IdentityCookies.REFRESH_NAME);
        clock.set(NOW.plusSeconds(86400));

        HttpResponse<String> response = send("POST", "/v1/session/refresh", refresh.header());

        assertProblem(response, 401, "REFRESH_TOKEN_INVALID");
    }

    @Test
    void refreshedAccessNeverOutlivesSession() throws Exception {
        ParsedCookie refresh = cookie(create(), IdentityCookies.REFRESH_NAME);
        Instant sessionExpiry = NOW.plusSeconds(86400);
        clock.set(sessionExpiry.minusSeconds(100));

        HttpResponse<String> response = send("POST", "/v1/session/refresh", refresh.header());

        assertThat(response.statusCode()).isEqualTo(200);
        assertNoStore(response);
        assertThat(response.headers().allValues("Set-Cookie")).hasSize(1);
        ParsedCookie access = cookie(response, IdentityCookies.ACCESS_NAME);
        assertCookieAttributes(access, "__Host-vitrin_at", "/", 100);
        assertThat(text(json(response), "accessExpiresAt")).isEqualTo(sessionExpiry.toString());
        SignedJWT jwt = SignedJWT.parse(access.value());
        assertThat(Objects.requireNonNull(jwt.getJWTClaimsSet().getExpirationTime()).toInstant())
                .isEqualTo(sessionExpiry);

        clock.set(sessionExpiry);
        assertProblem(send("GET", "/v1/session", access.header()), 401, "ACCESS_TOKEN_INVALID");
        assertProblem(
                send("POST", "/v1/session/refresh", refresh.header()),
                401,
                "REFRESH_TOKEN_INVALID");
    }

    @Test
    void unknownApiPathIsDeniedByDefaultAndIsNotCacheable() throws Exception {
        HttpResponse<String> response = send("GET", "/v1/anything", null);

        assertProblem(response, 401, "ACCESS_TOKEN_MISSING");
    }

    @Test
    void authenticatedUnknownApiPathRemainsNotCacheable() throws Exception {
        ParsedCookie access = cookie(create(), IdentityCookies.ACCESS_NAME);

        HttpResponse<String> response = send("GET", "/v1/anything", access.header());

        assertThat(response.statusCode()).isEqualTo(404);
        assertNoStore(response);
    }

    @Test
    void unsupportedMethodWithAccessCredentialIsNotCacheable() throws Exception {
        ParsedCookie access = cookie(create(), IdentityCookies.ACCESS_NAME);

        HttpResponse<String> response = send("PUT", "/v1/session", access.header());

        assertThat(response.statusCode()).isEqualTo(405);
        assertNoStore(response);
    }

    @Test
    void unsupportedMethodWithoutAccessCredentialIsDenied() throws Exception {
        HttpResponse<String> response = send("PUT", "/v1/session", null);

        assertProblem(response, 401, "ACCESS_TOKEN_MISSING");
    }

    @Test
    void rejectsRawParentTraversalPath() throws Exception {
        HttpResponse<String> response = send("GET", "/internal/../v1/session", null);

        assertProblem(response, 400, "PATH_NOT_ALLOWED");
    }

    @Test
    void rejectsRawDoubleSlashPath() throws Exception {
        HttpResponse<String> response = send("GET", "//v1/session", null);

        assertProblem(response, 400, "PATH_NOT_ALLOWED");
    }

    @Test
    void rejectsRawPathParameters() throws Exception {
        HttpResponse<String> response = send("GET", "/v1/session;x=1", null);

        assertProblem(response, 400, "PATH_NOT_ALLOWED");
    }

    @Test
    void decodedApiPathStillRequiresAccessCredential() throws Exception {
        HttpResponse<String> response = send("GET", "/%76%31/session", null);

        assertProblem(response, 401, "ACCESS_TOKEN_MISSING");
    }

    @Test
    void duplicateAccessCookiesAreRejectedWhenValidCookieComesFirst() throws Exception {
        ParsedCookie access = cookie(create(), IdentityCookies.ACCESS_NAME);
        String duplicates =
                access.header() + "; " + credential(IdentityCookies.ACCESS_NAME, "garbage");

        HttpResponse<String> response = send("GET", "/v1/session", duplicates);

        assertProblem(response, 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void duplicateAccessCookiesAreRejectedWhenValidCookieComesLast() throws Exception {
        ParsedCookie access = cookie(create(), IdentityCookies.ACCESS_NAME);
        String duplicates =
                credential(IdentityCookies.ACCESS_NAME, "garbage") + "; " + access.header();

        HttpResponse<String> response = send("GET", "/v1/session", duplicates);

        assertProblem(response, 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void duplicateRefreshCookiesAreRejected() throws Exception {
        ParsedCookie refresh = cookie(create(), IdentityCookies.REFRESH_NAME);

        for (String duplicates :
                List.of(
                        refresh.header()
                                + "; "
                                + credential(IdentityCookies.REFRESH_NAME, "garbage"),
                        credential(IdentityCookies.REFRESH_NAME, "garbage")
                                + "; "
                                + refresh.header())) {
            HttpResponse<String> response = send("POST", "/v1/session/refresh", duplicates);

            assertProblem(response, 401, "REFRESH_TOKEN_INVALID");
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        }
    }

    @Test
    void internalJwksPublishesOnePublicKeyThatVerifiesIssuedToken() throws Exception {
        HttpResponse<String> response = send("GET", "/internal/jwks", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.size()).isEqualTo(1);
        JsonNode publishedKeys = Objects.requireNonNull(body.get("keys"));
        assertThat(publishedKeys.isArray()).isTrue();
        assertThat(publishedKeys.size()).isEqualTo(1);
        JsonNode publicKeyJson = Objects.requireNonNull(publishedKeys.get(0));
        assertThat(publicKeyJson.size()).isEqualTo(7);
        assertThat(publicKeyJson.has("d")).isFalse();
        assertThat(text(publicKeyJson, "kty")).isEqualTo("EC");
        assertThat(text(publicKeyJson, "crv")).isEqualTo("P-256");
        assertThat(text(publicKeyJson, "use")).isEqualTo("sig");
        assertThat(text(publicKeyJson, "alg")).isEqualTo("ES256");
        assertThat(text(publicKeyJson, "x")).isNotBlank();
        assertThat(text(publicKeyJson, "y")).isNotBlank();

        JWKSet set = JWKSet.parse(response.body());
        ECKey jwk = (ECKey) set.getKeys().getFirst();
        assertThat(jwk.isPrivate()).isFalse();
        ECPublicKey publicKey = jwk.toECPublicKey();
        String keyId = Objects.requireNonNull(jwk.getKeyID());
        TokenVerifier verifier =
                new TokenVerifier(
                        properties.issuer(),
                        properties.audience(),
                        clock,
                        requestedId -> keyId.equals(requestedId) ? publicKey : null);

        HttpResponse<String> created = create();
        ParsedCookie access = cookie(created, IdentityCookies.ACCESS_NAME);
        ParsedCookie refresh = cookie(created, IdentityCookies.REFRESH_NAME);
        VerifiedToken verifiedAccess = verifier.verify(access.value(), TokenKind.ACCESS);
        VerifiedToken verifiedRefresh = verifier.verify(refresh.value(), TokenKind.REFRESH);

        assertThat(verifiedAccess.role()).isEqualTo(Role.GUEST_COMPANY);
        assertThat(verifiedAccess.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(verifiedRefresh.sessionId()).isEqualTo(verifiedAccess.sessionId());
        assertThat(verifiedRefresh.expiresAt()).isEqualTo(NOW.plusSeconds(86400));
    }

    private HttpResponse<String> create() throws Exception {
        HttpResponse<String> response = send("POST", "/v1/session", null);
        assertThat(response.statusCode()).isEqualTo(201);
        assertNoStore(response);
        return response;
    }

    private HttpResponse<String> send(String method, String path, @Nullable String cookies)
            throws Exception {
        return send(method, path, cookies, HttpRequest.BodyPublishers.noBody());
    }

    private HttpResponse<String> send(
            String method, String path, @Nullable String cookies, HttpRequest.BodyPublisher body)
            throws Exception {
        URI uri = URI.create("http://localhost:" + serverPort + path);
        HttpRequest.Builder request =
                HttpRequest.newBuilder(uri)
                        .version(HttpClient.Version.HTTP_1_1)
                        .method(method, body);
        if (cookies != null) {
            request.header("Cookie", cookies);
        }
        try (HttpClient client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            return client.send(
                    request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private JsonNode json(HttpResponse<String> response) {
        return Objects.requireNonNull(mapper.readTree(response.body()));
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertNoStore(response);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .isEqualTo("application/problem+json");
        JsonNode problem = json(response);
        assertThat(text(problem, "type")).isEqualTo("about:blank");
        assertThat(text(problem, "title"))
                .isEqualTo(status == 400 ? "Bad Request" : "Unauthorized");
        assertThat(Objects.requireNonNull(problem.get("status")).asInt()).isEqualTo(status);
        assertThat(text(problem, "code")).isEqualTo(code);
        if (status == 401) {
            assertThat(text(problem, "deniedBy")).isEqualTo("core");
        } else {
            assertThat(problem.has("deniedBy")).isFalse();
        }
        assertThat(problem.has("reason")).isFalse();
    }

    private static void assertNoStore(HttpResponse<String> response) {
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = Objects.requireNonNull(node.get(field));
        assertThat(value.isString()).isTrue();
        return value.asString();
    }

    private static ParsedCookie cookie(HttpResponse<String> response, String name) {
        List<ParsedCookie> matches =
                response.headers().allValues("Set-Cookie").stream()
                        .map(SessionEndpointsTests::parseCookie)
                        .filter(parsed -> parsed.name().equals(name))
                        .toList();
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    private static ParsedCookie parseCookie(String header) {
        List<String> parts = Arrays.asList(COOKIE_SEPARATOR.split(header));
        String credential = parts.getFirst();
        int equals = credential.indexOf('=');
        assertThat(equals).isPositive();
        String name = credential.substring(0, equals);
        String value = credential.substring(equals + 1);
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int index = 1; index < parts.size(); index++) {
            String part = parts.get(index);
            int separator = part.indexOf('=');
            String attributeName =
                    (separator < 0 ? part : part.substring(0, separator)).toLowerCase(Locale.ROOT);
            String attributeValue = separator < 0 ? "" : part.substring(separator + 1);
            assertThat(attributes).doesNotContainKey(attributeName);
            attributes.put(attributeName, attributeValue);
        }
        return new ParsedCookie(name, value, Map.copyOf(attributes));
    }

    private static void assertCookieAttributes(
            ParsedCookie cookie, String name, String path, long maxAge) {
        assertThat(cookie.name()).isEqualTo(name);
        assertThat(cookie.value()).isNotBlank();
        assertThat(cookie.attributes())
                .containsEntry("path", path)
                .containsEntry("secure", "")
                .containsEntry("httponly", "")
                .containsEntry("samesite", "Strict")
                .containsEntry("max-age", Long.toString(maxAge))
                .doesNotContainKey("domain")
                .containsOnlyKeys("path", "secure", "httponly", "samesite", "max-age", "expires");
        assertThat(Objects.requireNonNull(cookie.attributes().get("expires"))).isNotBlank();
    }

    private static String credential(String name, String value) {
        return name + "=" + value;
    }

    private static String tamperSignature(String compact) {
        int signatureStart = compact.lastIndexOf('.') + 1;
        int index = signatureStart + (compact.length() - signatureStart) / 2;
        char replacement = compact.charAt(index) == 'A' ? 'B' : 'A';
        return compact.substring(0, index) + replacement + compact.substring(index + 1);
    }

    private static String changeRoleWithoutResigning(String compact, String role) throws Exception {
        SignedJWT jwt = SignedJWT.parse(compact);
        Map<String, Object> claims =
                new LinkedHashMap<>(Objects.requireNonNull(jwt.getPayload().toJSONObject()));
        claims.put("role", role);
        return jwt.getHeader().toBase64URL()
                + "."
                + Base64URL.encode(JSONObjectUtils.toJSONString(claims))
                + "."
                + jwt.getSignature();
    }

    private String signAccessWithRole(String role) throws Exception {
        JWSObject object =
                new JWSObject(
                        new JWSHeader.Builder(JWSAlgorithm.ES256)
                                .type(new JOSEObjectType(TokenKind.ACCESS.headerType()))
                                .keyID(keys.activeKeyId())
                                .build(),
                        new Payload(
                                Map.of(
                                        "iss", properties.issuer(),
                                        "aud", List.of(properties.audience()),
                                        "sid", "0123456789abcdef0123456789abcdef",
                                        "role", role,
                                        "iat", NOW.getEpochSecond(),
                                        "exp", NOW.plusSeconds(900).getEpochSecond())));
        keys.sign(object);
        return object.serialize();
    }

    private record ParsedCookie(String name, String value, Map<String, String> attributes) {
        private ParsedCookie {
            attributes = Map.copyOf(attributes);
        }

        String header() {
            return credential(name, value);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableClock identityTestClock() {
            return new MutableClock(NOW);
        }
    }
}
