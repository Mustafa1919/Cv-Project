package com.mstech.vitrin.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.core.CoreApplication;
import com.mstech.vitrin.gateway.GatewayApplication;
import com.mstech.vitrin.gateway.testing.TestTokens;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class GatewayHarness implements AutoCloseable {
    public static final String ORIGIN = "https://site.test";

    private static final AtomicInteger ADDRESSES = new AtomicInteger();
    private static final HttpClient CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ConfigurableApplicationContext context;
    private final URI base;
    private final ShiftableClock clock;
    private final FakeUpstream search;

    private GatewayHarness(
            ConfigurableApplicationContext context, ShiftableClock clock, FakeUpstream search) {
        this.context = context;
        this.clock = clock;
        this.search = search;
        String port =
                Objects.requireNonNull(context.getEnvironment().getProperty("local.server.port"));
        base = URI.create("http://127.0.0.1:" + port);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return socket.getLocalPort();
        }
    }

    public static String nextAddress() {
        int index = ADDRESSES.getAndIncrement();
        String[] prefixes = {"198.51.100.", "203.0.113.", "192.0.2."};
        if (index >= prefixes.length * 254) {
            throw new IllegalStateException("Test address pool exhausted");
        }
        return prefixes[index / 254] + (index % 254 + 1);
    }

    public static void await(Duration timeout, BooleanSupplier condition) {
        long start = System.nanoTime();
        long budget = timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - start >= budget) {
                throw new AssertionError("Condition did not become true before the deadline");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new AssertionError("Interrupted while awaiting condition");
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
    }

    public HttpResponse<String> send(String method, String path, Map<String, String> headers)
            throws IOException, InterruptedException {
        return send(
                method,
                path,
                headers.entrySet().stream()
                        .map(entry -> new Header(entry.getKey(), entry.getValue()))
                        .toList());
    }

    public HttpResponse<String> send(String method, String path, List<Header> headers)
            throws IOException, InterruptedException {
        return send(method, path, headers, "");
    }

    public HttpResponse<String> send(String method, String path, List<Header> headers, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        for (Header header : headers) {
            request.header(header.name(), header.value());
        }
        HttpRequest.BodyPublisher publisher =
                body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        return CLIENT.send(
                request.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public ShiftableClock clock() {
        return clock;
    }

    public FakeUpstream search() {
        return search;
    }

    public JsonNode json(HttpResponse<String> response) {
        return Objects.requireNonNull(MAPPER.readTree(response.body()));
    }

    public void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type"))
                .contains("application/problem+json");
        JsonNode body = json(response);
        assertThat(body.path("type").asString()).isEqualTo("about:blank");
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asString()).isEqualTo(code);
        String trace = response.headers().firstValue("X-Trace-Id").orElseThrow();
        assertThat(trace).matches("[0-9a-f]{32}");
        assertThat(body.path("traceId").asString()).isEqualTo(trace);
        if (status != 401 && status != 403) {
            assertThat(body.has("deniedBy")).isFalse();
        }
    }

    public void assertProblem(
            HttpResponse<String> response, int status, String code, String deniedBy) {
        assertProblem(response, status, code);
        assertThat(json(response).path("deniedBy").asString()).isEqualTo(deniedBy);
    }

    public static String accessCookie(HttpResponse<String> response) {
        return cookie(response, "__Host-vitrin_at");
    }

    public static String refreshCookie(HttpResponse<String> response) {
        return cookie(response, "__Secure-vitrin_rt");
    }

    private static String cookie(HttpResponse<String> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .map(value -> value.split(";", 2)[0])
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected cookie was not issued: " + name));
    }

    public Session newSession(String address) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/v1/session", stateHeaders(address));
        assertThat(response.statusCode()).isEqualTo(201);
        return new Session(accessCookie(response), refreshCookie(response));
    }

    public static Map<String, String> stateHeaders(String address) {
        return Map.of(
                "CF-Connecting-IP", address,
                "Origin", ORIGIN,
                "X-Requested-With", "vitrin");
    }

    public static String tamperCookie(String cookie) {
        int equals = cookie.indexOf('=');
        if (equals < 1) {
            throw new IllegalArgumentException("Invalid cookie");
        }
        return cookie.substring(0, equals + 1)
                + TestTokens.tamperSignature(cookie.substring(equals + 1));
    }

    public static String changeRoleCookie(String cookie) {
        int equals = cookie.indexOf('=');
        if (equals < 1) {
            throw new IllegalArgumentException("Invalid cookie");
        }
        return cookie.substring(0, equals + 1)
                + TestTokens.changeRoleWithoutResigning(
                        cookie.substring(equals + 1), "demo_candidate");
    }

    public static String cookieValue(String cookie) {
        int equals = cookie.indexOf('=');
        if (equals < 1) {
            throw new IllegalArgumentException("Invalid cookie");
        }
        return cookie.substring(equals + 1);
    }

    @Override
    public void close() {
        try {
            context.close();
        } finally {
            search.close();
        }
    }

    public record Header(String name, String value) {}

    public record Session(String access, String refresh) {}

    public static final class ShiftableClock extends Clock {
        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        public void shift(Duration duration) {
            offset.updateAndGet(current -> current.plus(duration));
        }

        public void reset() {
            offset.set(Duration.ZERO);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset.get());
        }
    }

    public static final class RedisSupport {
        private RedisSupport() {}

        public static GenericContainer<?> shared() {
            return Holder.CONTAINER;
        }

        public static GenericContainer<?> separate() {
            GenericContainer<?> container =
                    new GenericContainer<>(DockerImageName.parse("redis:8.10.2-alpine"))
                            .withExposedPorts(6379);
            container.start();
            return container;
        }

        private static final class Holder {
            private static final GenericContainer<?> CONTAINER = start();

            private static GenericContainer<?> start() {
                GenericContainer<?> container = separate();
                Runtime.getRuntime()
                        .addShutdownHook(new Thread(container::stop, "http-test-redis-shutdown"));
                return container;
            }
        }
    }

    public static final class CoreInstance implements AutoCloseable {
        private final int port;
        private final int managementPort;
        private @Nullable ConfigurableApplicationContext context;

        public CoreInstance(int port, int managementPort) {
            this.port = port;
            this.managementPort = managementPort;
        }

        public void start() {
            if (context != null) {
                throw new IllegalStateException("Core is already running");
            }
            GenericContainer<?> redis = RedisSupport.shared();
            context =
                    new SpringApplicationBuilder(CoreApplication.class)
                            .run(
                                    "--spring.profiles.active=test",
                                    "--spring.application.name=core",
                                    "--spring.main.banner-mode=off",
                                    "--server.address=127.0.0.1",
                                    "--server.port=" + port,
                                    "--management.server.port=" + managementPort,
                                    "--spring.data.redis.host=" + redis.getHost(),
                                    "--spring.data.redis.port=" + redis.getMappedPort(6379));
        }

        public URI base() {
            return URI.create("http://127.0.0.1:" + port);
        }

        public URI health() {
            return URI.create("http://127.0.0.1:" + managementPort + "/actuator/health/readiness");
        }

        public int port() {
            return port;
        }

        public void stop() {
            ConfigurableApplicationContext running = context;
            context = null;
            if (running != null) {
                running.close();
            }
        }

        @Override
        public void close() {
            stop();
        }
    }

    public static final class FakeUpstream implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final ConcurrentLinkedQueue<Received> requests = new ConcurrentLinkedQueue<>();
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile Function<Received, Reply> handler =
                request -> new Reply(201, "{}", Map.of());
        private volatile boolean neverAnswers;

        public FakeUpstream() throws IOException {
            server =
                    HttpServer.create(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(executor);
            server.createContext(
                    "/",
                    exchange -> {
                        Map<String, List<String>> headers = new LinkedHashMap<>();
                        exchange.getRequestHeaders()
                                .forEach(
                                        (name, values) ->
                                                headers.put(
                                                        name.toLowerCase(Locale.ROOT),
                                                        List.copyOf(values)));
                        Received received =
                                new Received(
                                        exchange.getRequestMethod(),
                                        exchange.getRequestURI().getRawPath(),
                                        headers);
                        requests.add(received);
                        try (exchange) {
                            if (neverAnswers) {
                                try {
                                    release.await();
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                }
                                return;
                            }
                            Reply reply = handler.apply(received);
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            reply.headers()
                                    .forEach(
                                            (name, value) ->
                                                    exchange.getResponseHeaders().set(name, value));
                            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(reply.status(), body.length);
                            exchange.getResponseBody().write(body);
                        }
                    });
            server.start();
        }

        public URI base() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        public void respond(int status, String body, Map<String, String> headers) {
            handler = request -> new Reply(status, body, headers);
        }

        public void neverAnswer() {
            neverAnswers = true;
        }

        public List<Received> received() {
            return List.copyOf(requests);
        }

        public void clear() {
            requests.clear();
        }

        @Override
        public void close() {
            release.countDown();
            server.stop(0);
            executor.close();
        }

        public record Received(String method, String path, Map<String, List<String>> headers) {
            public Received {
                Map<String, List<String>> copied = new LinkedHashMap<>();
                headers.forEach((name, values) -> copied.put(name, List.copyOf(values)));
                headers = Collections.unmodifiableMap(copied);
            }
        }

        public record Reply(int status, String body, Map<String, String> headers) {
            public Reply {
                headers = Map.copyOf(headers);
            }
        }
    }

    public static final class Builder {
        private @Nullable CoreInstance core;
        private @Nullable URI upstream;
        private @Nullable String redisHost;
        private int redisPort;
        private final List<String> properties = new ArrayList<>();

        private Builder() {}

        public Builder core(CoreInstance instance) {
            core = instance;
            upstream = null;
            return this;
        }

        public Builder upstream(URI uri) {
            upstream = uri;
            core = null;
            return this;
        }

        public Builder redis(String host, int port) {
            redisHost = host;
            redisPort = port;
            return this;
        }

        public Builder property(String argument) {
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("Property must be a command-line argument");
            }
            properties.add(argument);
            return this;
        }

        public GatewayHarness start() throws IOException {
            CoreInstance configuredCore = core;
            URI configuredUpstream = upstream;
            URI base =
                    configuredCore != null
                            ? configuredCore.base()
                            : Objects.requireNonNull(configuredUpstream, "upstream");
            String host = redisHost;
            int port = redisPort;
            if (host == null) {
                GenericContainer<?> redis = RedisSupport.shared();
                host = redis.getHost();
                port = redis.getMappedPort(6379);
            }
            FakeUpstream search = new FakeUpstream();
            search.respond(200, "{}", Map.of());
            ShiftableClock clock = new ShiftableClock();
            Map<String, String> arguments = new LinkedHashMap<>();
            arguments.put("spring.profiles.active", "test");
            arguments.put("spring.application.name", "gateway");
            arguments.put("spring.main.banner-mode", "off");
            arguments.put("server.address", "127.0.0.1");
            arguments.put("server.port", "0");
            arguments.put("management.server.port", "0");
            arguments.put("vitrin.site.origin", ORIGIN);
            arguments.put("vitrin.gateway.core-base-url", base.toString());
            arguments.put(
                    "vitrin.gateway.core-health-url",
                    configuredCore != null
                            ? configuredCore.health().toString()
                            : base.resolve("/health").toString());
            arguments.put(
                    "vitrin.gateway.search-health-url",
                    search.base().resolve("/health").toString());
            arguments.put("vitrin.gateway.jwks-min-refresh-interval", "1ms");
            arguments.put("vitrin.gateway.status-cache-ttl", "1ms");
            arguments.put("vitrin.gateway.status-timeout", "200ms");
            arguments.put("spring.data.redis.host", host);
            arguments.put("spring.data.redis.port", Integer.toString(port));
            for (String group :
                    List.of(
                            "public",
                            "session-create",
                            "session-refresh",
                            "authenticated",
                            "preflight",
                            "session-read")) {
                arguments.put("vitrin.ratelimit.per-minute." + group, "10000");
            }
            for (String property : properties) {
                int equals = property.indexOf('=');
                if (equals < 3) {
                    search.close();
                    throw new IllegalArgumentException("Property must include a value");
                }
                arguments.put(property.substring(2, equals), property.substring(equals + 1));
            }
            String[] args =
                    arguments.entrySet().stream()
                            .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                            .toArray(String[]::new);
            try {
                ConfigurableApplicationContext context =
                        // Registered before refresh, so the platform's default clock backs off.
                        // A @Configuration class here would be picked up by every other test's
                        // component scan.
                        new SpringApplicationBuilder(GatewayApplication.class)
                                .initializers(
                                        applicationContext ->
                                                applicationContext
                                                        .getBeanFactory()
                                                        .registerSingleton("shiftableClock", clock))
                                .run(args);
                return new GatewayHarness(context, clock, search);
            } catch (RuntimeException | Error exception) {
                search.close();
                throw exception;
            }
        }
    }
}
