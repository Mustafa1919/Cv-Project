package com.mstech.vitrin.gateway.auth;

import com.mstech.vitrin.gateway.edge.GatewayProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

public final class HttpJwksClient implements JwksClient {
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final HttpClient client;
    private final HttpRequest request;

    public HttpJwksClient(GatewayProperties properties) {
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(properties.jwksTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        this.request =
                HttpRequest.newBuilder(properties.coreBaseUrl().resolve("/internal/jwks"))
                        .timeout(properties.jwksTimeout())
                        .header("Accept", "application/json")
                        .GET()
                        .build();
    }

    @Override
    public Map<String, ECPublicKey> fetch() throws IOException {
        final HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("JWKS request interrupted");
        }
        byte[] body;
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("JWKS response status rejected");
            }
            body = input.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new IOException("JWKS response exceeds size limit");
            }
        }
        return parse(body);
    }

    private static Map<String, ECPublicKey> parse(byte[] body) throws IOException {
        try {
            String json =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(body))
                            .toString();
            JWKSet set = JWKSet.parse(json);
            Map<String, ECPublicKey> keys = new HashMap<>();
            for (JWK key : set.getKeys()) {
                if (key instanceof ECKey ecKey && Curve.P_256.equals(ecKey.getCurve())) {
                    String thumbprint = ecKey.computeThumbprint().toString();
                    keys.put(thumbprint, ecKey.toECPublicKey());
                }
            }
            if (keys.isEmpty()) {
                throw new IOException("JWKS response contains no supported keys");
            }
            return Map.copyOf(keys);
        } catch (CharacterCodingException | ParseException | JOSEException exception) {
            throw new IOException("JWKS response cannot be parsed");
        } catch (RuntimeException exception) {
            throw new IOException("JWKS response cannot be parsed");
        }
    }
}
