package com.mstech.vitrin.gateway.edge;

import org.springframework.core.Ordered;

public final class FilterOrder {
    public static final int EDGE = Ordered.HIGHEST_PRECEDENCE;
    public static final int ROUTE = Ordered.HIGHEST_PRECEDENCE + 10;
    public static final int ADDRESS_QUOTA = Ordered.HIGHEST_PRECEDENCE + 20;
    public static final int PREFLIGHT = Ordered.HIGHEST_PRECEDENCE + 25;
    public static final int TOKEN = Ordered.HIGHEST_PRECEDENCE + 30;
    public static final int ROLE = Ordered.HIGHEST_PRECEDENCE + 40;
    public static final int STATE_CHANGE = Ordered.HIGHEST_PRECEDENCE + 50;
    public static final int SESSION_QUOTA = Ordered.HIGHEST_PRECEDENCE + 60;
    public static final int INPUT = Ordered.HIGHEST_PRECEDENCE + 70;

    private FilterOrder() {}
}
