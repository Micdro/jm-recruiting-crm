package com.janemichael.jmrecruitingcrm.common;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Hosting platforms (Render, Railway, Heroku, Fly) hand the app one DATABASE_URL such as
 * postgresql://user:password@host:5432/dbname. Spring needs a JDBC URL plus a separate user
 * and password, so this translates one into the other before the app starts.
 * Explicit SPRING_DATASOURCE_* variables always win.
 */
public final class DatabaseUrl {

    private DatabaseUrl() {
    }

    public static void apply(Map<String, String> environment) {
        toSpringProperties(environment).forEach(System::setProperty);
    }

    static Map<String, String> toSpringProperties(Map<String, String> environment) {
        Map<String, String> properties = new HashMap<>();
        String databaseUrl = environment.get("DATABASE_URL");

        if (databaseUrl == null || databaseUrl.isBlank() || environment.containsKey("SPRING_DATASOURCE_URL")) {
            return properties;
        }

        URI uri = URI.create(databaseUrl.trim());
        String scheme = uri.getScheme();
        if (!"postgres".equals(scheme) && !"postgresql".equals(scheme)) {
            return properties;
        }

        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        properties.put("spring.datasource.url", jdbcUrl);

        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            String user = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
            String password = colon >= 0 ? userInfo.substring(colon + 1) : null;

            if (!environment.containsKey("SPRING_DATASOURCE_USERNAME")) {
                properties.put("spring.datasource.username", decode(user));
            }
            if (password != null && !environment.containsKey("SPRING_DATASOURCE_PASSWORD")) {
                properties.put("spring.datasource.password", decode(password));
            }
        }

        return properties;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
