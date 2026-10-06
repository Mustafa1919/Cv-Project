package com.mstech.vitrin.gateway.status;

import com.mstech.vitrin.gateway.edge.GatewayProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

public final class StatusService {
    private final Clock clock;
    private final Duration cacheTtl;
    private final HttpClient client;
    private final HttpRequest coreRequest;
    private final HttpRequest searchRequest;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile @Nullable StatusSnapshot snapshot;

    public StatusService(GatewayProperties properties, Clock clock) {
        this.clock = clock;
        this.cacheTtl = properties.statusCacheTtl();
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(properties.statusTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        this.coreRequest = request(properties.coreHealthUrl(), properties.statusTimeout());
        this.searchRequest = request(properties.searchHealthUrl(), properties.statusTimeout());
    }

    public StatusSnapshot current() {
        StatusSnapshot observed = snapshot;
        if (observed != null && fresh(observed, clock.instant())) {
            return observed;
        }
        if (observed != null) {
            if (!refreshLock.tryLock()) {
                return observed;
            }
        } else {
            refreshLock.lock();
        }
        try {
            observed = snapshot;
            if (observed != null && fresh(observed, clock.instant())) {
                return observed;
            }
            StatusSnapshot refreshed = refresh();
            snapshot = refreshed;
            return refreshed;
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean fresh(StatusSnapshot value, Instant now) {
        return Duration.between(value.checkedAt(), now).compareTo(cacheTtl) < 0;
    }

    private StatusSnapshot refresh() {
        String core;
        String search;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> coreResult = executor.submit(() -> probe(coreRequest));
            Future<String> searchResult = executor.submit(() -> probe(searchRequest));
            core = result(coreResult);
            search = result(searchResult);
        }
        String status = "up".equals(core) && "up".equals(search) ? "ok" : "degraded";
        return new StatusSnapshot(
                status, Map.of("gateway", "up", "core", core, "search", search), clock.instant());
    }

    private String probe(HttpRequest request) {
        try {
            HttpResponse<Void> response =
                    client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200 ? "up" : "down";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "down";
        } catch (IOException | RuntimeException exception) {
            return "down";
        }
    }

    private static String result(Future<String> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "down";
        } catch (ExecutionException | CancellationException exception) {
            return "down";
        }
    }

    private static HttpRequest request(URI uri, Duration timeout) {
        return HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
    }
}
