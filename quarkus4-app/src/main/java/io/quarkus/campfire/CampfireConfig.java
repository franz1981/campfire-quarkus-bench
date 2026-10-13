package io.quarkus.campfire;

import java.math.BigInteger;
import java.util.Optional;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The environment the app reads, with the names and defaults of the Rust port's
 * {@code crates/campfire/src/config.rs} (and the Kotlin port's {@code Config}), read through
 * MicroProfile Config so environment variables and {@code application.properties} both work.
 */
public final class CampfireConfig {
    public final String secretKeyBase;
    public final String databasePath;
    public final boolean disableSsl;
    public final String appVersion;
    public final String gitRevision;
    public final String vapidPublicKey;
    public final int fragmentCacheBytes;
    /** {@code CAMPFIRE_RESPONSE_CACHE_MB}: the response cache's limit, 64 MB by default; zero disables it. */
    public final long responseCacheBytes;

    public CampfireConfig() {
        this(ConfigProvider.getConfig());
    }

    CampfireConfig(Config config) {
        secretKeyBase = value(config, "SECRET_KEY_BASE").orElseThrow(() -> new IllegalStateException("SECRET_KEY_BASE is required"));
        databasePath = config.getValue("campfire.database-path", String.class);
        disableSsl = value(config, "DISABLE_SSL").filter(v -> !v.isBlank()).isPresent();
        gitRevision = value(config, "GIT_REVISION").orElse(null);
        appVersion = value(config, "APP_VERSION").orElse(gitRevision != null ? gitRevision : "0");
        vapidPublicKey = value(config, "VAPID_PUBLIC_KEY").filter(v -> !v.isBlank())
                .filter(v -> value(config, "VAPID_PRIVATE_KEY").filter(p -> !p.isBlank()).isPresent()).orElse(null);
        fragmentCacheBytes = value(config, "CAMPFIRE_FRAGMENT_CACHE_MB").map(CampfireConfig::intOrNull).orElse(32) * 1024 * 1024;
        responseCacheBytes = megabytes(config, "CAMPFIRE_RESPONSE_CACHE_MB", 64);
    }

    private static Optional<String> value(Config config, String name) {
        return config.getOptionalValue(name, String.class).filter(v -> !v.isEmpty());
    }

    private static Integer intOrNull(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return 32;
        }
    }

    /** {@code number(name, default)} in {@code config.rs} (a trimmed {@code usize}) times a megabyte, saturating. */
    private static long megabytes(Config config, String name, long fallback) {
        Optional<String> raw = value(config, name);
        if (raw.isEmpty()) return fallback << 20;
        String digits = raw.get().trim();
        if (digits.startsWith("+")) digits = digits.substring(1);
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) throw new IllegalArgumentException(name + "=\"" + raw.get() + "\" is not a number");
        BigInteger number = new BigInteger(digits);
        if (number.bitLength() > 64) throw new IllegalArgumentException(name + "=\"" + raw.get() + "\" is not a number");
        return number.shiftLeft(20).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }
}
