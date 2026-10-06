package com.mstech.vitrin.gateway;

import com.mstech.vitrin.gateway.auth.AccessTokenFilter;
import com.mstech.vitrin.gateway.auth.HttpJwksClient;
import com.mstech.vitrin.gateway.auth.JwksClient;
import com.mstech.vitrin.gateway.auth.JwksKeySource;
import com.mstech.vitrin.gateway.auth.RoleFilter;
import com.mstech.vitrin.gateway.auth.StateChangeFilter;
import com.mstech.vitrin.gateway.edge.ClientAddressResolver;
import com.mstech.vitrin.gateway.edge.EdgeFilter;
import com.mstech.vitrin.gateway.edge.GatewayProblems;
import com.mstech.vitrin.gateway.edge.GatewayProperties;
import com.mstech.vitrin.gateway.edge.SiteProperties;
import com.mstech.vitrin.gateway.proxy.UpstreamFilter;
import com.mstech.vitrin.gateway.ratelimit.AddressHasher;
import com.mstech.vitrin.gateway.ratelimit.AddressQuotaFilter;
import com.mstech.vitrin.gateway.ratelimit.FallbackQuotaStore;
import com.mstech.vitrin.gateway.ratelimit.MemoryQuotaStore;
import com.mstech.vitrin.gateway.ratelimit.RateLimitProperties;
import com.mstech.vitrin.gateway.ratelimit.RedisQuotaStore;
import com.mstech.vitrin.gateway.ratelimit.SessionQuotaFilter;
import com.mstech.vitrin.gateway.route.CorsPolicy;
import com.mstech.vitrin.gateway.route.InputFilter;
import com.mstech.vitrin.gateway.route.PreflightFilter;
import com.mstech.vitrin.gateway.route.RouteFilter;
import com.mstech.vitrin.gateway.route.RouteRule;
import com.mstech.vitrin.gateway.route.RouteTable;
import com.mstech.vitrin.gateway.status.StatusService;
import com.mstech.vitrin.platform.token.TokenVerifier;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class GatewayConfiguration {
    @Bean
    public RouteTable routeTable(RateLimitProperties properties) {
        RouteTable table = RouteTable.pillarZero();
        properties.limitFor("preflight");
        for (RouteRule rule : table.rules()) {
            String addressGroup = rule.addressQuotaGroup();
            if (addressGroup != null) {
                properties.limitFor(addressGroup);
            }
            String sessionGroup = rule.sessionQuotaGroup();
            if (sessionGroup != null) {
                properties.limitFor(sessionGroup);
            }
        }
        return table;
    }

    @Bean
    public GatewayProblems gatewayProblems(JsonMapper mapper, ObjectProvider<Tracer> tracers) {
        return new GatewayProblems(mapper, tracers);
    }

    @Bean
    public ClientAddressResolver clientAddressResolver(GatewayProperties properties) {
        return new ClientAddressResolver(properties);
    }

    @Bean
    public CorsPolicy corsPolicy(SiteProperties properties) {
        return new CorsPolicy(properties);
    }

    @Bean
    public JwksClient jwksClient(GatewayProperties properties) {
        return new HttpJwksClient(properties);
    }

    @Bean
    public JwksKeySource jwksKeySource(
            JwksClient client, Clock clock, GatewayProperties properties) {
        return new JwksKeySource(client, clock, properties);
    }

    @Bean
    public ApplicationListener<ApplicationReadyEvent> jwksWarmUp(JwksKeySource keys) {
        AtomicBoolean started = new AtomicBoolean();
        return event -> {
            if (started.compareAndSet(false, true)) {
                keys.warmUp();
            }
        };
    }

    @Bean
    public TokenVerifier tokenVerifier(
            GatewayProperties properties, Clock clock, JwksKeySource keys) {
        return new TokenVerifier(properties.tokenIssuer(), properties.tokenAudience(), clock, keys);
    }

    @Bean
    @DependsOn("startupGuard")
    public AddressHasher addressHasher(RateLimitProperties properties, Environment environment) {
        return AddressHasher.load(
                properties.addressHashKeyFile(), environment.acceptsProfiles(Profiles.of("prod")));
    }

    @Bean(destroyMethod = "close")
    public RedisQuotaStore redisQuotaStore(
            LettuceConnectionFactory connectionFactory, RateLimitProperties properties) {
        return new RedisQuotaStore(
                () -> {
                    AbstractRedisClient nativeClient = connectionFactory.getRequiredNativeClient();
                    if (!(nativeClient instanceof RedisClient redisClient)) {
                        throw new IllegalStateException("Standalone Redis is required");
                    }
                    return redisClient.connect(
                            RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
                },
                properties.redisTimeout());
    }

    @Bean
    public MemoryQuotaStore memoryQuotaStore(RateLimitProperties properties) {
        return new MemoryQuotaStore(properties.fallbackMaxEntries());
    }

    @Bean
    @Primary
    public FallbackQuotaStore quotaStore(
            RedisQuotaStore primary,
            MemoryQuotaStore fallback,
            Clock clock,
            RateLimitProperties properties) {
        return new FallbackQuotaStore(primary, fallback, clock, properties.redisRetryInterval());
    }

    @Bean
    public EdgeFilter edgeFilter(ClientAddressResolver addresses, GatewayProperties properties) {
        return new EdgeFilter(addresses, properties);
    }

    @Bean
    public RouteFilter routeFilter(
            RouteTable routes,
            CorsPolicy cors,
            GatewayProblems problems,
            ObjectProvider<Tracer> tracers) {
        return new RouteFilter(routes, cors, problems, tracers);
    }

    @Bean
    public AddressQuotaFilter addressQuotaFilter(
            FallbackQuotaStore quotas,
            RateLimitProperties properties,
            AddressHasher hasher,
            GatewayProblems problems) {
        return new AddressQuotaFilter(quotas, properties, hasher, problems);
    }

    @Bean
    public PreflightFilter preflightFilter(CorsPolicy cors, GatewayProblems problems) {
        return new PreflightFilter(cors, problems);
    }

    @Bean
    public AccessTokenFilter accessTokenFilter(
            TokenVerifier verifier, JwksKeySource keys, GatewayProblems problems) {
        return new AccessTokenFilter(verifier, keys, problems);
    }

    @Bean
    public RoleFilter roleFilter(GatewayProblems problems) {
        return new RoleFilter(problems);
    }

    @Bean
    public StateChangeFilter stateChangeFilter(CorsPolicy cors, GatewayProblems problems) {
        return new StateChangeFilter(cors, problems);
    }

    @Bean
    public SessionQuotaFilter sessionQuotaFilter(
            FallbackQuotaStore quotas, RateLimitProperties properties, GatewayProblems problems) {
        return new SessionQuotaFilter(quotas, properties, problems);
    }

    @Bean
    public InputFilter inputFilter(GatewayProblems problems) {
        return new InputFilter(problems);
    }

    @Bean
    public UpstreamFilter upstreamFilter(GatewayProblems problems) {
        return new UpstreamFilter(problems);
    }

    @Bean
    public StatusService statusService(GatewayProperties properties, Clock clock) {
        return new StatusService(properties, clock);
    }
}
