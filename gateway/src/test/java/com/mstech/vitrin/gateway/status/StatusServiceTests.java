package com.mstech.vitrin.gateway.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.testing.MutableClock;
import com.mstech.vitrin.gateway.testing.TestSettings;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StatusServiceTests {
    private static final Duration TIMEOUT = Duration.ofMillis(200);
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final List<HttpServer> servers = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();
    private final List<CountDownLatch> blockedHandlers = new ArrayList<>();

    @AfterEach
    void releaseHandlersAndStopServers() {
        for (CountDownLatch latch : blockedHandlers) {
            latch.countDown();
        }
        try {
            for (HttpServer server : servers) {
                server.stop(0);
            }
        } finally {
            for (ExecutorService executor : executors) {
                executor.close();
            }
        }
    }

    @Test
    void healthyComponentsProduceOkInContractIterationOrder() throws IOException {
        Endpoint core = endpoint(200);
        Endpoint search = endpoint(200);

        StatusSnapshot snapshot = service(core.uri(), search.uri()).current();

        assertThat(snapshot.status()).isEqualTo("ok");
        assertThat(snapshot.components()).containsExactlyEntriesOf(orderedComponents("up", "up"));
        assertThat(snapshot.components().keySet()).containsExactly("gateway", "core", "search");
        assertThat(snapshot.checkedAt()).isEqualTo(clock.instant());
        assertThat(core.hits().get()).isEqualTo(1);
        assertThat(search.hits().get()).isEqualTo(1);
    }

    @Test
    void nonSuccessfulSearchHealthProducesDegraded() throws IOException {
        Endpoint core = endpoint(200);
        Endpoint search = endpoint(503);

        StatusSnapshot snapshot = service(core.uri(), search.uri()).current();

        assertThat(snapshot.status()).isEqualTo("degraded");
        assertThat(snapshot.components()).containsExactlyEntriesOf(orderedComponents("up", "down"));
    }

    @Test
    void coreConnectionRefusalMarksCoreDown() throws IOException {
        Endpoint search = endpoint(200);
        HttpServer reservation =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        URI unavailable = uri(reservation);
        reservation.start();
        reservation.stop(0);

        StatusSnapshot snapshot = service(unavailable, search.uri()).current();

        assertThat(snapshot.status()).isEqualTo("degraded");
        assertThat(snapshot.components()).containsExactlyEntriesOf(orderedComponents("down", "up"));
    }

    @Test
    void aHealthEndpointThatNeverAnswersIsBoundedByTheRequestTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Endpoint core = blockedEndpoint(entered, release);
        Endpoint search = endpoint(200);
        StatusService service = service(core.uri(), search.uri());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<StatusSnapshot> result = executor.submit(service::current);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                long start = System.nanoTime();

                StatusSnapshot snapshot = result.get(2, TimeUnit.SECONDS);

                assertThat(System.nanoTime() - start).isLessThan(Duration.ofSeconds(2).toNanos());
                assertThat(snapshot.status()).isEqualTo("degraded");
                assertThat(snapshot.components())
                        .containsExactlyEntriesOf(orderedComponents("down", "up"));
                assertThat(core.hits().get()).isEqualTo(1);
            } finally {
                release.countDown();
                boolean cancelled = result.cancel(true);
                assertThat(cancelled || result.isDone()).isTrue();
            }
        }
    }

    @Test
    void secondCallInsideCacheTtlDoesNotProbeAgain() throws IOException {
        Endpoint core = endpoint(200);
        Endpoint search = endpoint(200);
        StatusService service = service(core.uri(), search.uri());
        StatusSnapshot first = service.current();
        clock.advance(Duration.ofSeconds(4));

        StatusSnapshot second = service.current();

        assertThat(second).isSameAs(first);
        assertThat(core.hits().get()).isEqualTo(1);
        assertThat(search.hits().get()).isEqualTo(1);
    }

    @Test
    void advancingPastCacheTtlProbesBothComponentsAgain() throws IOException {
        Endpoint core = endpoint(200);
        Endpoint search = endpoint(200);
        StatusService service = service(core.uri(), search.uri());
        StatusSnapshot first = service.current();
        clock.advance(Duration.ofSeconds(6));

        StatusSnapshot second = service.current();

        assertThat(second).isNotSameAs(first);
        assertThat(second.status()).isEqualTo("ok");
        assertThat(second.checkedAt()).isEqualTo(clock.instant());
        assertThat(core.hits().get()).isEqualTo(2);
        assertThat(search.hits().get()).isEqualTo(2);
    }

    @Test
    void serializedSnapshotExposesOnlyTheThreeContractFields() throws IOException {
        Endpoint core = endpoint(200);
        Endpoint search = endpoint(200);
        StatusSnapshot snapshot = service(core.uri(), search.uri()).current();
        JsonMapper mapper = JsonMapper.builder().build();

        JsonNode body = Objects.requireNonNull(mapper.readTree(mapper.writeValueAsBytes(snapshot)));

        List<String> fields = new ArrayList<>();
        body.properties().forEach(entry -> fields.add(entry.getKey()));
        assertThat(fields).containsExactlyInAnyOrder("status", "components", "checkedAt");
        assertThat(body.path("status").asString()).isEqualTo("ok");
        assertThat(body.path("components").path("gateway").asString()).isEqualTo("up");
        assertThat(body.path("components").path("core").asString()).isEqualTo("up");
        assertThat(body.path("components").path("search").asString()).isEqualTo("up");
        assertThat(body.has("checkedAt")).isTrue();
        assertThat(body.path("checkedAt").isNull()).isFalse();
    }

    private StatusService service(URI coreHealth, URI searchHealth) {
        return new StatusService(
                TestSettings.gateway(
                        coreHealth.resolve("/"), coreHealth, searchHealth, TIMEOUT, CACHE_TTL),
                clock);
    }

    private Endpoint endpoint(int status) throws IOException {
        HttpServer server = newServer();
        AtomicInteger hits = new AtomicInteger();
        server.createContext(
                "/health",
                exchange -> {
                    hits.incrementAndGet();
                    try (exchange) {
                        exchange.sendResponseHeaders(status, -1);
                    }
                });
        server.start();
        return new Endpoint(uri(server), hits);
    }

    private Endpoint blockedEndpoint(CountDownLatch entered, CountDownLatch release)
            throws IOException {
        HttpServer server = newServer();
        AtomicInteger hits = new AtomicInteger();
        blockedHandlers.add(release);
        server.createContext(
                "/health",
                exchange -> {
                    hits.incrementAndGet();
                    entered.countDown();
                    try (exchange) {
                        try {
                            release.await();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        server.start();
        return new Endpoint(uri(server), hits);
    }

    private HttpServer newServer() throws IOException {
        HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        servers.add(server);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executors.add(executor);
        server.setExecutor(executor);
        return server;
    }

    private static URI uri(HttpServer server) {
        String address = server.getAddress().getAddress().getHostAddress();
        String host = address.contains(":") ? "[" + address + "]" : address;
        return URI.create("http://" + host + ":" + server.getAddress().getPort() + "/health");
    }

    private static Map<String, String> orderedComponents(String core, String search) {
        Map<String, String> components = new LinkedHashMap<>();
        components.put("gateway", "up");
        components.put("core", core);
        components.put("search", search);
        return components;
    }

    private record Endpoint(URI uri, AtomicInteger hits) {}
}
