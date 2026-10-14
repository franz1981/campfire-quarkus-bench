package io.quarkus.campfire;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;

import campfire.kit.ChunkedBody;
import campfire.kit.CookieJar;
import campfire.kit.Headers;
import campfire.kit.HttpDate;
import campfire.kit.Request;
import campfire.kit.Response;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;
import io.vertx.core.Context;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.internal.buffer.BufferInternal;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import kotlin.Pair;
import campfire.routes.Recognition;
import campfire.routes.RouteTable;
import campfire.routes.RouteTableKt;
import campfire.ruby.RubyIntegerKt;

/**
 * Every request on Quarkus' HTTP server goes to {@link CampfireApp#handler}, as the Kotlin port's
 * Netty server ({@code campfire.kit.HttpServer}) hands it on, and the response is written the way
 * that server writes it: the handler's headers in order, then {@code Date}, a zero
 * {@code Content-Length} last, chunked when the body is marked {@link ChunkedBody}.
 *
 * <p>SQLite runs only on the {@link SqliteStage}; cache hits run on the event loops, and the requests
 * that need more (misses, other routes) on the {@link RequestPool}, their SQL forwarded to the stage.
 */
@Singleton
public class HttpRoutes {
    private static final Logger LOG = Logger.getLogger(HttpRoutes.class);

    @Inject
    CampfireApp app;

    @Inject
    SqliteStage stage;

    @Inject
    RequestPool pool;

    void routes(@Observes Router router) {
        router.route().handler(rc -> withBody(rc.request(), this::staged));
    }

    /**
     * The room page's reads on the {@link SqliteStage}, everything else on the event loop: a signed-in
     * {@code GET /rooms/:id} gets its session, user and membership (and the generations around them)
     * from the stage, then runs on the event loop. A request that needs any other SQLite access (a
     * miss, the hourly session refresh), or that isn't such a GET, runs on the {@link RequestPool}
     * from a fresh copy, its SQL forwarded to the stage.
     */
    private void staged(HttpServerRequest http, byte[] body) {
        Context loop = Vertx.currentContext();
        Request request = toRequest(http, body);
        String sessionCookie = plainGet(request) ? sessionCookie(request) : null;
        String token = sessionCookie == null ? null : app.signedCookies.verify("session_token", sessionCookie, app.kit.getJavaClock().instant());
        long roomId = token == null ? -1 : roomId(request.getPath());
        if (roomId < 0) {
            onPool(loop, http, request);
            return;
        }
        stage.lookup(token, sessionCookie, roomId, loop, prefetched -> {
            if (prefetched.failed) {
                onPool(loop, http, request);
                return;
            }
            Staged.set(prefetched);
            Response response;
            try {
                response = handle(request);
            } finally {
                Staged.clear();
            }
            if (prefetched.needsStage) onPool(loop, http, toRequest(http, body));
            else respond(http, response);
        });
    }

    /**
     * {@code request} in full on the {@link RequestPool}, from scratch: its generation and reads are
     * observed afresh there (reusing the event loop's batch would serve it at a generation that, under
     * frequent commits, is already gone, and nothing it renders could be kept).
     */
    private void onPool(Context loop, HttpServerRequest http, Request request) {
        try {
            pool.execute(() -> {
                Response response = handle(request);
                loop.runOnContext(ignored -> respond(http, response));
            });
        } catch (RejectedExecutionException e) {
            overloaded(http);
        }
    }

    /** Requests refused because the request pool's queue was full, and when that was last logged. */
    private static final java.util.concurrent.atomic.LongAdder REFUSED = new java.util.concurrent.atomic.LongAdder();
    private static volatile long lastRefusalLog;

    /** The request pool's queue is full: 503, try again in a second (on the request's event loop); logged at most once a second. */
    private static void overloaded(HttpServerRequest http) {
        REFUSED.increment();
        long now = System.nanoTime();
        if (now - lastRefusalLog > 1_000_000_000L) {
            lastRefusalLog = now;
            LOG.warnf("request pool full: 503 (%d refused so far)", REFUSED.sum());
        }
        http.response().setStatusCode(503).putHeader("retry-after", "1").putHeader("content-length", "0").end();
    }

    /** Writes {@code response}; if that fails, the connection still gets an answer (or is closed). */
    private static void respond(HttpServerRequest http, Response response) {
        try {
            write(http, response);
        } catch (Throwable t) {
            LOG.error("writing the response failed", t);
            if (!http.response().headWritten()) http.response().setStatusCode(500).end();
            else http.connection().close();
        }
    }

    /** A plain {@code GET}: no query, no body (the only requests whose reads are prefetched). */
    private static boolean plainGet(Request request) {
        return request.getMethod().equals("GET") && request.getQuery() == null && request.getBody().length == 0;
    }

    private static final String ROOMS = "/rooms/";

    /**
     * The room id of a {@code rooms#show} request, or -1. The canonical {@code /rooms/<digits>} is read
     * directly, as the route table reads it ({@code RoutesTest}); any other path is recognized by the
     * route table itself, as the kit's router will.
     */
    static long roomId(String path) {
        int n = path.length();
        if (path.startsWith(ROOMS) && n > ROOMS.length() && n - ROOMS.length() <= 18) {
            long id = 0;
            int i = ROOMS.length();
            while (i < n && path.charAt(i) >= '0' && path.charAt(i) <= '9') id = id * 10 + (path.charAt(i++) - '0');
            if (i == n) return id;
        }
        if (!(RouteTable.Companion.getCampfire().recognize("GET", RouteTableKt.normalizePath(path)) instanceof Recognition.Matched matched)
                || !matched.getEndpoint().equals("rooms#show")) return -1;
        String id = matched.getParams().get("id");
        Long cast = id == null ? null : RubyIntegerKt.integerCast(id);
        return cast == null ? -1 : cast;
    }

    /**
     * The {@code session_token} cookie's value as the kit's cookie jar holds it, or null:
     * {@code CookieJar.parseCookieHeader} over each {@code Cookie} header (pairs split on {@code ;},
     * spaces skipped after a header's first pair, the first occurrence of a name wins), without
     * building the jar. A value the jar would unescape ({@code %} or {@code +} in it) is left to the
     * jar itself ({@code CookiesTest}).
     */
    private String sessionCookie(Request request) {
        List<String> headers = request.getHeaders().all("cookie");
        String raw = sessionCookie(headers);
        if (raw == null || (raw.indexOf('%') < 0 && raw.indexOf('+') < 0)) return raw;
        return CookieJar.Companion.fromHeaders(headers, app.kit.getCookieCrypto(), app.kit.getJavaClock()).get("session_token");
    }

    /** The first {@code session_token} pair's raw (still escaped) value across {@code headers}, or null. */
    static String sessionCookie(List<String> headers) {
        for (String header : headers) {
            int start = 0;
            boolean first = true;
            while (start <= header.length()) {
                int end = header.indexOf(';', start);
                if (end < 0) end = header.length();
                int from = start;
                if (!first) while (from < end && header.charAt(from) == ' ') from++;
                first = false;
                if (from < end) {
                    int eq = header.indexOf('=', from);
                    if (eq >= end) eq = -1;
                    int keyEnd = eq < 0 ? end : eq;
                    if (keyEnd - from == SESSION_TOKEN.length() && header.startsWith(SESSION_TOKEN, from)) return eq < 0 ? "" : header.substring(eq + 1, end);
                }
                start = end + 1;
            }
        }
        return null;
    }

    private static final String SESSION_TOKEN = "session_token";

    /** Hands the request on once its body is read; the router paused the request, so it's resumed to read it. */
    private static void withBody(HttpServerRequest request, BiConsumer<HttpServerRequest, byte[]> serve) {
        if (request.isEnded()) {
            serve.accept(request, Request.Companion.getEMPTY());
            return;
        }
        request.body().onSuccess(buffer -> serve.accept(request, buffer.length() == 0 ? Request.Companion.getEMPTY() : buffer.getBytes()))
                .onFailure(e -> request.response().setStatusCode(400).end());
        request.resume();
    }

    private Response handle(Request request) {
        try {
            return app.handler.handle(request);
        } catch (Throwable e) {
            SqliteStage.Prefetched staged = Staged.current();
            if (staged == null || !staged.needsStage) LOG.error("request failed", e);
            return Response.Companion.text(500, "Internal Server Error", "text/plain; charset=utf-8");
        }
    }

    private static Request toRequest(HttpServerRequest request, byte[] body) {
        Headers headers = new Headers();
        request.headers().forEach(headers::add);
        String version = request.version() == HttpVersion.HTTP_1_0 ? "HTTP/1.0" : "HTTP/1.1";
        return new Request(request.method().name(), request.uri(), headers, body, remote(request.remoteAddress()), version);
    }

    private static InetSocketAddress remote(SocketAddress address) {
        if (address == null || address.hostAddress() == null) return null;
        return new InetSocketAddress(InetAddress.ofLiteral(address.hostAddress()), address.port());
    }

    /**
     * Writes {@code response} as {@code HttpServer.write} does: with a {@code Content-Length} from the
     * body unless it has one (none for 1xx, 204, 304, or an empty HEAD response), or chunked when
     * it's marked {@link ChunkedBody}; {@code Date} when it has none.
     */
    private static void write(HttpServerRequest request, Response response) {
        HttpServerResponse out = request.response();
        int status = response.getStatus();
        boolean head = request.method() == HttpMethod.HEAD;
        boolean http10 = request.version() == HttpVersion.HTTP_1_0;
        boolean bodiless = (status >= 100 && status <= 199) || status == 204 || status == 304;
        out.setStatusCode(status);
        MultiMap headers = out.headers();
        copyHeaders(response, headers);
        ByteBuf page = head ? null : cachedPage(response);
        Buffer content = page != null ? BufferInternal.buffer(page) : head ? null : content(response.getBody().chunks());
        if (!bodiless && response.getExtensions().containsKey(ChunkedBody.class)) {
            headers.remove(HttpHeaders.CONTENT_LENGTH);
            if (!head && !http10) out.setChunked(true);
        } else {
            long length = response.getBody().getLength();
            if (!bodiless && !(head && length == 0) && !headers.contains(HttpHeaders.CONTENT_LENGTH) && !headers.contains(HttpHeaders.TRANSFER_ENCODING)) {
                headers.set(HttpHeaders.CONTENT_LENGTH, Long.toString(length));
            }
        }
        if (content == null) out.end();
        else if (page == null) out.end(content);
        else out.end(content).onComplete(written -> page.release());
    }

    /**
     * A cache hit's body as the entry's own direct buffer ({@link ResponseCache.Hit}), with a reference
     * held for this write, or null: when the body is no longer the cached array, or the entry has
     * released the buffer meanwhile. The caller releases it once the write completes: Vert.x hands
     * Netty an unreleasable slice of a buffer it didn't allocate ({@code BufferImpl.getByteBuf}), so
     * Netty's release after the write doesn't reach it.
     */
    private static ByteBuf cachedPage(Response response) {
        List<byte[]> chunks = response.getBody().chunks();
        if (response.getExtensions().get(ResponseCache.Hit.class) instanceof ResponseCache.Hit hit
                && chunks.size() == 1 && chunks.getFirst() == hit.source()) {
            return hit.retainedPage();
        }
        return null;
    }

    /** The body's buffers as one Vert.x buffer, without copying (the epoll transport copies heap buffers into a direct one to write). */
    private static Buffer content(List<byte[]> chunks) {
        if (chunks.isEmpty()) return null;
        ByteBuf buf = chunks.size() == 1 ? Unpooled.wrappedBuffer(chunks.getFirst()) : Unpooled.wrappedBuffer(chunks.toArray(new byte[0][]));
        return BufferInternal.buffer(buf);
    }

    /**
     * The response's headers in order, then {@code Date}; a zero {@code Content-Length} goes last, as
     * the Rust port's responses carry it. Vert.x writes an {@link AsciiString} with one array copy and
     * a {@code String} a character at a time, straight into the response buffer either way. So what is
     * reused goes in encoded: names ({@link #NAMES}) and a cache hit's values, encoded once when its
     * entry was kept ({@link ResponseCache.Hit}); a value of this response alone (cookies, request id,
     * runtime, date) goes in as its {@code String}, as the Kotlin port hands Netty its headers.
     */
    private static void copyHeaders(Response response, MultiMap headers) {
        Map<String, AsciiString> cached = response.getExtensions().get(ResponseCache.Hit.class) instanceof ResponseCache.Hit hit ? hit.values() : Map.of();
        boolean zeroLength = false;
        for (Pair<? extends String, ? extends String> header : response.getHeaders()) {
            if (header.getFirst().equals("content-length") && header.getSecond().equals("0")) {
                zeroLength = true;
                continue;
            }
            AsciiString value = cached.get(header.getSecond());
            headers.add(name(header.getFirst()), value != null ? value : header.getSecond());
        }
        if (!headers.contains(HttpHeaders.DATE)) headers.add(HttpHeaders.DATE, HttpDate.INSTANCE.now());
        if (zeroLength) headers.add(HttpHeaders.CONTENT_LENGTH, "0");
    }

    /** A header value encoded as Vert.x and Netty write a {@code String} one ({@code AsciiString.c2b}: Latin-1, else {@code ?}). */
    static AsciiString ascii(String value) {
        return new AsciiString(value);
    }

    /** The header names the app sends, pre-encoded; any other name is encoded per response. */
    private static final Map<String, AsciiString> NAMES = new HashMap<>();

    static {
        for (String name : List.of("content-type", "content-length", "content-encoding", "vary", "x-version", "x-rev", "link", "set-cookie",
                "x-frame-options", "x-xss-protection", "x-content-type-options", "x-permitted-cross-domain-policies", "referrer-policy", "etag",
                "cache-control", "x-request-id", "x-runtime", "x-cache", "location", "last-modified", "accept-ranges", "content-range",
                "transfer-encoding", "strict-transport-security", "date", "expires", "content-disposition")) {
            NAMES.put(name, AsciiString.cached(name));
        }
    }

    /** The pre-encoded name, or the name itself (written a character at a time) when the app doesn't usually send it. */
    private static CharSequence name(String name) {
        AsciiString known = NAMES.get(name);
        return known != null ? known : name;
    }
}
