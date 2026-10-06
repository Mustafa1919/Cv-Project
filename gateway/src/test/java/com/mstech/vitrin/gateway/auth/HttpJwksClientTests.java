package com.mstech.vitrin.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import com.mstech.vitrin.gateway.testing.TestSettings;
import com.mstech.vitrin.gateway.testing.TestTokens;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpJwksClientTests {
    private @Nullable HttpServer server;

    @AfterEach
    void stopServer() {
        HttpServer running = server;
        if (running != null) {
            running.stop(0);
        }
    }

    @Test
    void parsesPublicKeysUsingComputedThumbprintInsteadOfDocumentKid()
            throws GeneralSecurityException, JOSEException, IOException {
        TestTokens tokens = new TestTokens();
        ECKey lying = new ECKey.Builder(Curve.P_256, tokens.publicKey()).keyID("lying-kid").build();
        HttpJwksClient client = serve(200, new JWKSet(lying).toString());

        assertThat(client.fetch())
                .containsExactlyEntriesOf(tokens.keyMap())
                .doesNotContainKey("lying-kid");
    }

    @Test
    void rejectsNonSuccessfulStatus() throws IOException {
        HttpJwksClient client = serve(500, "{}");

        assertThatIOException().isThrownBy(client::fetch);
    }

    @Test
    void rejectsBodyLargerThanSixtyFourKibibytes() throws IOException {
        HttpJwksClient client = serve(200, " ".repeat(65_537));

        assertThatIOException().isThrownBy(client::fetch);
    }

    @Test
    void rejectsNonJsonBody() throws IOException {
        HttpJwksClient client = serve(200, "not JSON");

        assertThatIOException().isThrownBy(client::fetch);
    }

    @Test
    void rejectsASetContainingOnlyRsa() throws GeneralSecurityException, IOException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).build();
        HttpJwksClient client = serve(200, new JWKSet(key).toString());

        assertThatIOException().isThrownBy(client::fetch);
    }

    @Test
    void rejectsASetContainingOnlyP384() throws GeneralSecurityException, IOException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair pair = generator.generateKeyPair();
        ECKey key = new ECKey.Builder(Curve.P_384, (ECPublicKey) pair.getPublic()).build();
        HttpJwksClient client = serve(200, new JWKSet(key).toString());

        assertThatIOException().isThrownBy(client::fetch);
    }

    @Test
    void doesNotFollowRedirects() throws IOException, GeneralSecurityException, JOSEException {
        TestTokens tokens = new TestTokens();
        HttpServer running =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server = running;
        AtomicInteger redirectedHits = new AtomicInteger();
        byte[] body = tokens.jwksJson().getBytes(StandardCharsets.UTF_8);
        running.createContext(
                "/internal/jwks",
                exchange -> {
                    try (exchange) {
                        exchange.getResponseHeaders().set("Location", "/actual");
                        exchange.sendResponseHeaders(302, -1);
                    }
                });
        running.createContext(
                "/actual",
                exchange -> {
                    redirectedHits.incrementAndGet();
                    try (exchange) {
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        running.start();
        HttpJwksClient client = client(base(running));

        assertThatIOException().isThrownBy(client::fetch);
        assertThat(redirectedHits.get()).isZero();
    }

    @Test
    void connectionRefusalIsAnIOException() throws IOException {
        HttpServer reservation =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        URI base = base(reservation);
        reservation.start();
        reservation.stop(0);
        HttpJwksClient client = client(base);

        assertThatIOException().isThrownBy(client::fetch);
    }

    private HttpJwksClient serve(int status, String text) throws IOException {
        HttpServer running =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server = running;
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        running.createContext(
                "/internal/jwks",
                exchange -> {
                    try (exchange) {
                        exchange.sendResponseHeaders(status, body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        running.start();
        return client(base(running));
    }

    private static URI base(HttpServer running) {
        return URI.create("http://127.0.0.1:" + running.getAddress().getPort());
    }

    private static HttpJwksClient client(URI base) {
        return new HttpJwksClient(
                TestSettings.gateway(
                        base,
                        base.resolve("/health"),
                        base.resolve("/search"),
                        Duration.ofMillis(200),
                        Duration.ofSeconds(5)));
    }
}
