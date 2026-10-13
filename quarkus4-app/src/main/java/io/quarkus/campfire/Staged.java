package io.quarkus.campfire;

import campfire.kit.Body;
import campfire.kit.ErrorsKt;
import campfire.kit.Headers;
import campfire.kit.Response;
import io.vertx.core.Context;

/**
 * The prefetched reads of the request running on this thread (an event loop), and the guard that
 * keeps SQLite off event loops: a request that needs anything else from SQLite is marked and halted,
 * and runs again on the {@link SqliteStage}.
 */
final class Staged {
    private static final ThreadLocal<SqliteStage.Prefetched> CURRENT = new ThreadLocal<>();

    private Staged() {
    }

    static SqliteStage.Prefetched current() {
        return CURRENT.get();
    }

    /** The current request's prefetched reads; an event loop has no other way to SQLite. */
    static SqliteStage.Prefetched prefetched() {
        SqliteStage.Prefetched p = CURRENT.get();
        if (p == null) throw new IllegalStateException("SQLite access on an event loop outside a staged request");
        return p;
    }

    static void set(SqliteStage.Prefetched prefetched) {
        CURRENT.set(prefetched);
    }

    static void clear() {
        CURRENT.remove();
    }

    /** Marks the current request for the SQLite stage and halts it; never returns. */
    static <T> T needStage() {
        prefetched().needsStage = true;
        ErrorsKt.halt(new Response(503, new Headers(), Body.Empty.INSTANCE));
        throw new IllegalStateException("halt returned");
    }

    /** Called before writing or observing SQLite directly: on an event loop, the request goes to the stage instead. */
    static void requireBlockingAllowed() {
        if (Context.isOnEventLoopThread()) needStage();
    }
}
