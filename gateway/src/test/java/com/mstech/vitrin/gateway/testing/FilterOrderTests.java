package com.mstech.vitrin.gateway.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.auth.AccessTokenFilter;
import com.mstech.vitrin.gateway.auth.RoleFilter;
import com.mstech.vitrin.gateway.auth.StateChangeFilter;
import com.mstech.vitrin.gateway.edge.EdgeFilter;
import com.mstech.vitrin.gateway.edge.FilterOrder;
import com.mstech.vitrin.gateway.ratelimit.AddressQuotaFilter;
import com.mstech.vitrin.gateway.ratelimit.SessionQuotaFilter;
import com.mstech.vitrin.gateway.route.InputFilter;
import com.mstech.vitrin.gateway.route.PreflightFilter;
import com.mstech.vitrin.gateway.route.RouteFilter;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

class FilterOrderTests {
    @Test
    void constantsAreStrictlyIncreasing() {
        int[] orders = {
            FilterOrder.EDGE,
            FilterOrder.ROUTE,
            FilterOrder.ADDRESS_QUOTA,
            FilterOrder.PREFLIGHT,
            FilterOrder.TOKEN,
            FilterOrder.ROLE,
            FilterOrder.STATE_CHANGE,
            FilterOrder.SESSION_QUOTA,
            FilterOrder.INPUT
        };
        for (int index = 1; index < orders.length; index++) {
            assertThat(orders[index]).isGreaterThan(orders[index - 1]);
        }
    }

    @Test
    void edgePrecedesTracingAndRouteFollowsTracing() {
        assertThat(FilterOrder.EDGE).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(FilterOrder.ROUTE).isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 1);
    }

    @ParameterizedTest
    @MethodSource("filterOrders")
    void filterCarriesItsContractOrder(Class<?> filter, int expected) {
        Order annotation = Objects.requireNonNull(filter.getDeclaredAnnotation(Order.class));
        assertThat(annotation.value()).isEqualTo(expected);
    }

    static Stream<Arguments> filterOrders() {
        return Stream.of(
                Arguments.of(EdgeFilter.class, FilterOrder.EDGE),
                Arguments.of(RouteFilter.class, FilterOrder.ROUTE),
                Arguments.of(AddressQuotaFilter.class, FilterOrder.ADDRESS_QUOTA),
                Arguments.of(PreflightFilter.class, FilterOrder.PREFLIGHT),
                Arguments.of(AccessTokenFilter.class, FilterOrder.TOKEN),
                Arguments.of(RoleFilter.class, FilterOrder.ROLE),
                Arguments.of(StateChangeFilter.class, FilterOrder.STATE_CHANGE),
                Arguments.of(SessionQuotaFilter.class, FilterOrder.SESSION_QUOTA),
                Arguments.of(InputFilter.class, FilterOrder.INPUT));
    }
}
