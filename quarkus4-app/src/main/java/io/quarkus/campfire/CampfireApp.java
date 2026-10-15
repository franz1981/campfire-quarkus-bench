package io.quarkus.campfire;

import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import campfire.app.PublicFilesKt;
import campfire.kit.Action;
import campfire.kit.Adapter;
import campfire.kit.Ctx;
import campfire.kit.ErrorPages;
import campfire.kit.Handler;
import campfire.kit.HttpKt;
import campfire.kit.Kit;
import campfire.kit.KitConfig;
import campfire.kit.RackDeflater;
import campfire.kit.Router;
import campfire.kit.front.Front;
import campfire.kit.front.FrontConfig;
import campfire.kit.front.LogSink;
import campfire.kit.front.MemoryCache;
import campfire.rails.Secrets;
import campfire.rails.SystemClock;
import campfire.routes.RouteTable;
import campfire.views.FragmentCache;

/**
 * The application state actions reach through the kit ({@code AppState} in
 * {@code crates/campfire/src/app.rs}), and the request handler the HTTP route runs: the front server
 * (Thruster's job), the response cache's {@code completed}, {@code Rack::Deflater}, the Rails
 * middleware, {@code public/} files and the route table, as the Rust port stacks them.
 */
@Singleton
public class CampfireApp {
    private static final Logger LOG = Logger.getLogger(CampfireApp.class);

    final CampfireConfig config = new CampfireConfig();
    final Secrets secrets = new Secrets(config.secretKeyBase);
    final SignedCookies signedCookies = new SignedCookies(secrets);
    final FragmentCache fragments = new FragmentCache(config.fragmentCacheBytes);
    ResponseCache.Store responseCache;
    FrontConfig front;
    Handler handler;
    Kit kit;

    @Inject
    Db db;

    @PostConstruct
    void init() {
        responseCache = new ResponseCache.Store(db.dataVersion(), config.responseCacheBytes);
        if (config.nativeFragments) campfire.kit.Bytes.Companion.setStore(NativeBytes.STORE);
        if (config.ffmDeflate) {
            campfire.kit.PageParts.setDeflate(FfmZlib.INSTANCE);
            LOG.infof("pieces deflated by the system zlib %s through FFM", FfmZlib.version());
        }
        front = FrontConfig.Companion.fromEnv();
        KitConfig production = KitConfig.Companion.production(config.disableSsl);
        KitConfig kitConfig = new KitConfig(production.getProxy(), production.getForceSsl(), production.getHsts(), production.getSession(),
                production.getForgeryProtectionOriginCheck(), production.getDefaultHeaders(), ErrorPages.Companion.getPUBLIC(), production.getMaxBodyBytes());
        kit = new Kit(kitConfig, secrets, SystemClock.INSTANCE, this, LogSink.Companion.getSTDERR());
        Map<String, Action> actions = Map.of("rooms#show", ResponseCache.around("rooms#show", RoomsController.show));
        Action router = ResponseCache.snapshotting(new Router(actions, RouteTable.Companion.getCampfire(), Router.Companion.getACTION_NOT_FOUND()));
        Handler dispatch = request -> Adapter.INSTANCE.dispatch(kit, request, router, Router.Companion.getCATCH_ALL());
        Handler app = HttpKt.layered(Adapter.INSTANCE.app(kit, dispatch, PublicFilesKt.getPublicFiles()), ResponseCache.completed, new RackDeflater());
        handler = HttpKt.layered(app, new Front(front, CampfireApp::logRequest, new MemoryCache(front.getCacheSize(), front.getMaxCacheItemSize())));
    }

    /** The front server's request log, less the first attempt of a request that ran again on the SQLite stage. */
    private static void logRequest(String line) {
        SqliteStage.Prefetched staged = Staged.current();
        if (staged == null || !staged.needsStage) System.err.println(line);
    }

    static CampfireApp of(Ctx c) {
        return (CampfireApp) c.getKit().getState();
    }
}
