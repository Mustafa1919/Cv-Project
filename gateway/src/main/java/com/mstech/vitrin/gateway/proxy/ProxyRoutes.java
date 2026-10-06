package com.mstech.vitrin.gateway.proxy;

import com.mstech.vitrin.gateway.edge.GatewayProperties;
import com.mstech.vitrin.gateway.route.RouteRule;
import com.mstech.vitrin.gateway.route.RouteTable;
import com.mstech.vitrin.gateway.route.Upstream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration(proxyBeanMethods = false)
public class ProxyRoutes {
    @Bean
    public RouterFunction<ServerResponse> coreRoutes(
            RouteTable table, GatewayProperties properties, UpstreamFilter upstreamFilter) {
        List<RouterFunction<ServerResponse>> routes = new ArrayList<>();
        for (RouteRule rule : table.rules()) {
            if (rule.upstream() == Upstream.CORE) {
                RouterFunction<ServerResponse> route =
                        GatewayRouterFunctions.route(rule.id())
                                .route(
                                        RequestPredicates.method(HttpMethod.valueOf(rule.method()))
                                                .and(RequestPredicates.path(rule.path())),
                                        HandlerFunctions.http())
                                .before(BeforeFilterFunctions.uri(properties.coreBaseUrl()))
                                .filter(upstreamFilter)
                                .build();
                routes.add(route);
            }
        }
        return routes.stream()
                .reduce((left, right) -> left.and(right))
                .orElseThrow(() -> new IllegalStateException("No core routes configured"));
    }
}
