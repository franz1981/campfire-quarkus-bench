package io.quarkus.campfire;

import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.vertx.http.HttpServerConfigCustomizer;
import io.vertx.core.http.HttpServerConfig;

/**
 * Vert.x's strict thread mode for the HTTP server ({@code campfire.http.strict-thread-mode}, on by
 * default): every response is written on its connection's event loop, which this app already does
 * ({@code HttpRoutes} hands work back with {@code runOnContext}; Vert.x throws otherwise). In return a
 * connection writes straight to Netty instead of through its own outbound queue: Vert.x 5.1.8 gives
 * every connection an {@code OutboundMessageQueue} whose MPSC queue preallocates a 1024-slot array
 * (about 4 KB per connection, 40% of a connection's live heap at 16k connections).
 */
@Singleton
public class StrictThreadMode implements HttpServerConfigCustomizer {
    @ConfigProperty(name = "campfire.http.strict-thread-mode", defaultValue = "true")
    boolean enabled;

    @Override
    public void customizeHttpServer(HttpServerConfig config) {
        config.setStrictThreadMode(enabled);
    }
}
