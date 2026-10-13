package io.quarkus.campfire;

import campfire.app.browser.AllowBrowserCheck;
import campfire.app.browser.AllowBrowserKt;
import campfire.app.browser.ApplicationPlatform;
import campfire.db.DatabaseKt;
import campfire.db.Session;
import campfire.db.User;
import campfire.kit.Cookie;
import campfire.kit.Ctx;
import campfire.kit.ErrorsKt;
import campfire.kit.KitError;
import campfire.kit.Param;
import campfire.kit.Redirect;
import campfire.kit.SameSite;
import campfire.ruby.RubyStringKt;

/** {@code ApplicationController}'s before-actions ({@code crates/campfire/src/concerns.rs}, the Kotlin port's {@code Concerns.kt}). */
final class Concerns {
    private Concerns() {
    }

    /** How the request authenticated ({@code Authentication#authenticated_by}). */
    enum AuthenticatedBy { NONE, SESSION, BOT_KEY }

    /** {@code Current.user}. */
    record CurrentUser(User user) {
    }

    /** {@code Current.session}. */
    record CurrentSession(Session session) {
    }

    /** The before-actions in the order the reference runs them, with authentication required, bots denied and forgery protection on. */
    static void beforeActions(Ctx c) {
        setVersionHeaders(c);
        rejectBannedIp(c);
        requireAuthentication(c);
        denyBots(c);
        if (authenticatedBy(c) != AuthenticatedBy.BOT_KEY) c.verifyAuthenticityToken();
        allowBrowser(c);
    }

    /** {@code set_version_headers}: {@code X-Version}, and {@code X-Rev} unless it's nil. */
    static void setVersionHeaders(Ctx c) {
        CampfireConfig config = CampfireApp.of(c).config;
        c.setHeader("x-version", config.appVersion);
        if (config.gitRevision != null) c.setHeader("x-rev", config.gitRevision);
    }

    /** {@code reject_banned_ip}, unless the request is safe (GET/HEAD): 429 for a banned {@code remote_ip}. */
    static void rejectBannedIp(Ctx c) {
        if (c.getRequest().isGet() || c.getRequest().isHead()) return;
        String ip = c.getRequest().remoteIp();
        if (CampfireApp.of(c).db.read(r -> r.banned(ip))) ErrorsKt.halt(c.head(429));
    }

    /** {@code require_authentication}: {@code restore_authentication || bot_authentication || request_authentication}. */
    static void requireAuthentication(Ctx c) {
        if (restoreAuthentication(c) || botAuthentication(c)) return;
        requestAuthentication(c);
    }

    /**
     * {@code restore_authentication}: resume the session the {@code session_token} cookie names. A
     * staged request's cookie was already verified when its reads were prefetched; the same raw
     * value isn't verified (HMAC) twice.
     */
    static boolean restoreAuthentication(Ctx c) {
        SqliteStage.Prefetched p = Staged.current();
        String raw = c.getCookies().get("session_token");
        String token = p != null && raw != null && raw.equals(p.rawCookie) ? p.token : c.getCookies().signed("session_token");
        if (token == null) return false;
        Session session = CampfireApp.of(c).db.read(r -> r.sessionByToken(token));
        if (session == null) return false;
        resumeSession(c, session);
        return true;
    }

    /** {@code bot_authentication}: {@code params[:bot_key].present?} and a matching active bot. */
    static boolean botAuthentication(Ctx c) {
        Param param = c.param("bot_key");
        if (param == null || !param.isPresent()) return false;
        String key = param.asStr();
        if (key == null) throw new KitError.Internal(new IllegalStateException("undefined method 'strip' for bot_key"));
        User bot = CampfireApp.of(c).db.read(r -> r.authenticateBot(RubyStringKt.strip(key)));
        if (bot == null) return false;
        Current.set(c, CurrentUser.class, new CurrentUser(bot));
        Current.set(c, AuthenticatedBy.class, AuthenticatedBy.BOT_KEY);
        return true;
    }

    /** {@code request_authentication}: remember where we were, then off to sign in. */
    static void requestAuthentication(Ctx c) {
        c.session().set("return_to_after_authenticating", c.getRequest().url());
        ErrorsKt.halt(c.redirectTo(c.urlFor("/session/new"), new Redirect()));
    }

    /**
     * {@code resume_session(session)}: refresh its activity at most hourly, then authenticate as it.
     * Only a session due for its refresh goes to the writer, and the {@code session_token} cookie is
     * re-signed on the same schedule, as the Rust port does.
     */
    static void resumeSession(Ctx c, Session session) {
        CampfireApp app = CampfireApp.of(c);
        boolean refresh = session.needsResume(c.now());
        if (refresh) {
            app.db.touchSession(session.getId(), c.getRequest().remoteIp(), c.getRequest().getUserAgent(), DatabaseKt.dbTime(c.now()));
        }
        User user = app.db.read(r -> r.user(session.getUserId()));
        if (refresh) c.getCookies().setSigned("session_token", cookie(session.getToken()).permanent().httponly().sameSite(SameSite.LAX));
        Current.set(c, CurrentSession.class, new CurrentSession(session));
        if (user != null) Current.set(c, CurrentUser.class, new CurrentUser(user));
        Current.set(c, AuthenticatedBy.class, AuthenticatedBy.SESSION);
    }

    static AuthenticatedBy authenticatedBy(Ctx c) {
        AuthenticatedBy by = Current.get(c, AuthenticatedBy.class);
        return by != null ? by : AuthenticatedBy.NONE;
    }

    static User currentUser(Ctx c) {
        CurrentUser current = Current.get(c, CurrentUser.class);
        return current != null ? current.user() : null;
    }

    /** {@code deny_bots}: 403 for bot-key requests. */
    static void denyBots(Ctx c) {
        if (authenticatedBy(c) == AuthenticatedBy.BOT_KEY) ErrorsKt.halt(c.head(403));
    }

    /** {@code allow_browser}: the platform parsed for the check is kept for the layout. */
    static void allowBrowser(Ctx c) {
        AllowBrowserCheck check = AllowBrowserKt.allowBrowser(c.getRequest().getUserAgent());
        if (check == null) return;
        Current.set(c, ApplicationPlatform.class, check.getPlatform());
        if (check.getBlocked()) ErrorsKt.halt(RoomsController.renderIncompatibleBrowser(c));
    }

    /** {@code platform}: the one {@link #allowBrowser} kept, or a new one. */
    static ApplicationPlatform platform(Ctx c) {
        ApplicationPlatform kept = Current.get(c, ApplicationPlatform.class);
        return kept != null ? kept : new ApplicationPlatform(c.getRequest().getUserAgent());
    }

    /** {@code remember_last_room_visited}: {@code cookies.permanent[:last_room] = @room.id}, only when it changes. */
    static void rememberLastRoomVisited(Ctx c, long roomId) {
        String id = Long.toString(roomId);
        if (!id.equals(c.getCookies().get("last_room"))) c.getCookies().set("last_room", cookie(id).permanent());
    }

    /** {@code Cookie(value)} with the Kotlin defaults for everything else. */
    static Cookie cookie(String value) {
        return new Cookie(value, "/", null, null, false, false, false, SameSite.LAX, false);
    }
}
