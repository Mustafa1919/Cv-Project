package com.mstech.vitrin.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class SearchApplicationTests {

    @Value("${local.server.port}")
    private int serverPort;

    @Value("${local.management.port}")
    private int managementPort;

    @Autowired private Clock clock;

    @Test
    void readinessIsServedOnManagementPort() throws Exception {
        assertThat(status(managementPort, "/actuator/health/readiness")).isEqualTo(200);
    }

    @Test
    void actuatorIsNotServedOnApplicationPort() throws Exception {
        assertThat(status(serverPort, "/actuator/health")).isEqualTo(404);
    }

    @Test
    void onlyHealthIsExposed() throws Exception {
        assertThat(status(managementPort, "/actuator/env")).isEqualTo(404);
    }

    @Test
    void clockIsUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    private static int status(int port, String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
