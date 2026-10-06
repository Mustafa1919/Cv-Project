package com.mstech.vitrin.gateway.edge;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Enumeration;
import org.jspecify.annotations.Nullable;

public final class ClientAddressResolver {
    private final String headerName;

    public ClientAddressResolver(GatewayProperties properties) {
        this.headerName = properties.clientAddressHeader();
    }

    public ClientAddress resolve(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(headerName);
        if (values != null && values.hasMoreElements()) {
            String value = values.nextElement();
            if (!values.hasMoreElements()) {
                ClientAddress address = parse(value);
                if (address != null) {
                    return address;
                }
            }
        }
        ClientAddress fallback = parse(request.getRemoteAddr());
        return fallback == null ? new ClientAddress("unknown", false) : fallback;
    }

    private static @Nullable ClientAddress parse(@Nullable String literal) {
        if (literal == null || literal.indexOf('%') >= 0) {
            return null;
        }
        final InetAddress address;
        try {
            address = InetAddress.ofLiteral(literal);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return new ClientAddress(address.getHostAddress(), false);
        }
        if (isIpv4Mapped(bytes)) {
            return render(Arrays.copyOfRange(bytes, 12, 16), false);
        }
        Arrays.fill(bytes, 8, 16, (byte) 0);
        return render(bytes, true);
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        if (bytes.length != 16 || bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable ClientAddress render(byte[] bytes, boolean ipv6) {
        try {
            return new ClientAddress(InetAddress.getByAddress(bytes).getHostAddress(), ipv6);
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
