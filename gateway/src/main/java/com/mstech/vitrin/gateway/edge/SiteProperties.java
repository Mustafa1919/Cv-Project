package com.mstech.vitrin.gateway.edge;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("vitrin.site")
public record SiteProperties(@DefaultValue("http://localhost:4321") String origin) {
    public SiteProperties {
        if (!validOrigin(origin)) {
            throw new IllegalArgumentException("Invalid vitrin.site.origin");
        }
    }

    private static boolean validOrigin(@Nullable String value) {
        if (value == null || !value.equals(value.toLowerCase(Locale.ROOT))) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getRawPath();
            int port = uri.getPort();
            if ((!"http".equals(scheme) && !"https".equals(scheme))
                    || host == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (path != null && !path.isEmpty())
                    || port < -1
                    || port > 65535) {
                return false;
            }
            String authority = host + (port == -1 ? "" : ":" + port);
            return value.equals(scheme + "://" + authority);
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
