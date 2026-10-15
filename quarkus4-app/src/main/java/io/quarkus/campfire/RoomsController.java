package io.quarkus.campfire;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import campfire.assets.Assets;
import campfire.db.LayoutAccount;
import campfire.db.Message;
import campfire.db.MessageKey;
import campfire.db.PageMessage;
import campfire.db.Room;
import campfire.db.RoomKind;
import campfire.db.User;
import campfire.kit.Action;
import campfire.kit.Ctx;
import campfire.kit.ErrorsKt;
import campfire.kit.Flash;
import campfire.kit.Fragment;
import campfire.kit.Mime;
import campfire.kit.PageParts;
import campfire.kit.Redirect;
import campfire.kit.Response;
import campfire.rails.GlobalId;
import campfire.rails.Secrets;
import campfire.rails.SecretsKt;
import campfire.rails.SignedGlobalId;
import campfire.richtext.AttachableResolver;
import campfire.richtext.GidLookup;
import campfire.richtext.MentionUser;
import campfire.richtext.Presentation;
import campfire.richtext.RenderContext;
import campfire.richtext.RichText;
import campfire.richtext.RichTextError;
import campfire.richtext.SignedLookup;
import campfire.ruby.RubyIntegerKt;
import campfire.views.BoostView;
import campfire.views.FragmentCache;
import campfire.views.FragmentKeys;
import campfire.views.LayoutContext;
import campfire.views.MessageView;
import campfire.views.ShowView;
import campfire.views.Unsupported;
import campfire.views.UserView;
import campfire.views.ViewsKt;

/** {@code RoomsController} ({@code crates/campfire/src/controllers/rooms.rs}, the Kotlin port's {@code RoomsController.kt}). */
final class RoomsController {
    private RoomsController() {
    }

    /**
     * {@code show}: a response the {@link ResponseCache} kept for this request is served once
     * authentication, the room's permission check and the {@code last_room} cookie have run.
     */
    static final Action show = c -> {
        Concerns.beforeActions(c);
        Room room = setRoom(c);
        Concerns.rememberLastRoomVisited(c, room.getId());
        Response cached = ResponseCache.lookup(c);
        return cached != null ? cached : renderShow(c, room);
    };

    /** {@code set_room}: {@code room_scope.find_by(id: params[:room_id] || params[:id])}, or back to the root with an alert. */
    private static Room setRoom(Ctx c) {
        User user = Concerns.currentUser(c);
        if (user == null) throw new IllegalStateException("set_room without Current.user");
        String param = c.paramStr("room_id");
        if (param == null) param = c.paramStr("id");
        Long id = param != null ? RubyIntegerKt.integerCast(param) : null;
        Room room = id != null ? CampfireApp.of(c).db.read(r -> r.roomForUser(user.getId(), id)) : null;
        if (room == null) ErrorsKt.halt(c.redirectTo(c.urlFor("/"), new Redirect(null, null, "Room not found or inaccessible", false)));
        return room;
    }

    /** {@code rooms/show} with {@code find_messages}: the last page. */
    private static Response renderShow(Ctx c, Room room) {
        c.respondTo(Mime.Companion.getHTML());
        if (c.paramStr("message_id") != null) throw new Unsupported("the page around a message");
        CampfireApp app = CampfireApp.of(c);
        FragmentCache fragments = ResponseCache.fragments(c);
        User user = Concerns.currentUser(c);
        Instant now = c.now();
        String host = c.getRequest().host();
        String lastRoomCookie = c.getCookies().get("last_room");
        Long lastRoom = lastRoomCookie != null ? RubyIntegerKt.integerCast(lastRoomCookie) : null;
        Runnable prefetched = app.db.prefetchRoomPage(room, user.getId(), lastRoom, key -> fragments.get(messageKey(key), Fragment.class));
        try {
            return renderShow(c, app, room, fragments, user, now, host);
        } finally {
            prefetched.run();
        }
    }

    private static Response renderShow(Ctx c, CampfireApp app, Room room, FragmentCache fragments, User user, Instant now, String host) {
        PAGES.increment();
        ShowView show = app.db.read(r -> {
            List<PageMessage<Fragment>> messages = r.lastPage(room.getId(), key -> fragments.get(messageKey(key), Fragment.class));
            Presenter presenter = new Presenter(r, app, fragments, now, host);
            Long original = r.originalRoomId();
            List<Fragment> rendered = new ArrayList<>(messages.size());
            for (PageMessage<Fragment> message : messages) rendered.add(presenter.fragment(message));
            return new ShowView(room.getId(), room.getKind(), displayName(r, room, user), room.getUpdatedAt().epochMs(), rendered,
                    original != null && original == room.getId() && !r.paged(room.getId()), orEmpty(r.accountJoinCode()),
                    app.secrets.signedStreamName(SecretsKt.globalIdParam(room.getKind().getModelName(), room.getId()), "messages"));
        });
        boolean frame = c.isTurboFrameRequest();
        PageParts parts = ViewsKt.renderRoomShow(layout(c), show, frame);
        if (!frame) addPreloadLinks(c);
        return c.renderPage(200, Mime.Companion.getHTML(), parts);
    }

    /** {@code Layout::load}: the account, its logo, the last room visited, and the flash, as the layout shows them. */
    static LayoutContext layout(Ctx c) {
        CampfireApp app = CampfireApp.of(c);
        User user = Concerns.currentUser(c);
        Flash flash = c.flash();
        String lastRoomCookie = c.getCookies().get("last_room");
        Long lastRoom = lastRoomCookie != null ? RubyIntegerKt.integerCast(lastRoomCookie) : null;
        return app.db.read(r -> {
            LayoutAccount account = r.layoutAccount();
            boolean hasLogo = account != null && r.hasAttachedBlob("Account", account.getId(), "logo");
            Long visited = lastRoom != null ? r.roomIdForUser(user.getId(), lastRoom) : null;
            if (visited == null) r.firstRoomIdForUser(user.getId());
            return new LayoutContext(userView(app, user), user.getAdministrator(), c.getRequest().getProtocol() + c.getRequest().hostWithPort(),
                    "/account/logo?v=" + (account != null ? account.getUpdatedAt().number() : ""), hasLogo,
                    account != null ? account.getCustomStyles() : null, app.config.vapidPublicKey, Concerns.platform(c).toView(),
                    flash.getStr("notice"), flash.getStr("alert"));
        });
    }

    /** The {@code Link} preload header {@code stylesheet_link_tag} adds for a page in the application layout. */
    static void addPreloadLinks(Ctx c) {
        String existing = c.getHeaders().get("link");
        c.setHeader("link", existing == null || existing.isEmpty() ? Assets.INSTANCE.getLinkHeader()
                : Assets.INSTANCE.appendPreloadLinks(existing, Assets.INSTANCE.getStylesheetTagsAll().getPreloadLinks()));
    }

    /** {@code render template: "sessions/incompatible_browser"} (200, HTML whatever the format). */
    static Response renderIncompatibleBrowser(Ctx c) {
        boolean frame = c.isTurboFrameRequest();
        PageParts parts = campfire.views.LayoutKt.renderIncompatibleBrowser(layout(c), frame);
        if (!frame) addPreloadLinks(c);
        return c.renderPage(200, Mime.Companion.getHTML(), parts);
    }

    /** {@code cache [ message, "presentation-v3" ]}'s key for the message's version. */
    private static String messageKey(MessageKey key) {
        return FragmentKeys.INSTANCE.record("messages/_message", "messages", key.getId(), key.getVersion(), "presentation-v3");
    }

    /** {@code String#all_emoji?} (reference/lib/rails_ext/string.rb). */
    private static final Pattern ALL_EMOJI = Pattern.compile("\\A(\\p{IsEmoji_Presentation}|\\p{IsExtended_Pictographic}|\\x{FE0F})+\\z");

    static UserView userView(CampfireApp app, User user) {
        return new UserView(user.getId(), user.getName(), user.getTitle(), "/users/" + app.secrets.avatarToken(user.getId()) + "/avatar?v=" + user.getUpdatedAt().number());
    }

    /** {@code room_display_name(room, for_user:)}. */
    private static String displayName(Reads r, Room room, User forUser) {
        if (room.getKind() != RoomKind.Direct) return orEmpty(room.getName());
        List<String> names = new ArrayList<>();
        for (User u : r.roomUsers(room.getId())) if (forUser == null || u.getId() != forUser.getId()) names.add(u.getName());
        String sentence = switch (names.size()) {
            case 0 -> "";
            case 1 -> names.get(0);
            case 2 -> names.get(0) + " and " + names.get(1);
            default -> String.join(", ", names.subList(0, names.size() - 1)) + ", and " + names.getLast();
        };
        return sentence.isBlank() ? (forUser != null ? forUser.getName() : "") : sentence;
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }

    /** Room pages rendered, and their message fragments: found by the page's reads, found in the cache, rendered (the stage's statistics). */
    static final java.util.concurrent.atomic.LongAdder PAGES = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder FRAGMENTS_PREFETCHED = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder FRAGMENTS_CACHED = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder FRAGMENTS_RENDERED = new java.util.concurrent.atomic.LongAdder();
    /** Rendered fragments another render had stored first, and the time renders took (render, encode, keep). */
    static final java.util.concurrent.atomic.LongAdder FRAGMENTS_RACED = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder RENDER_NANOS = new java.util.concurrent.atomic.LongAdder();

    /** Looks rows up for a page of messages, remembering the rooms and users it has seen. */
    private static final class Presenter {
        private final Reads r;
        private final CampfireApp app;
        private final FragmentCache fragments;
        private final RenderContext richText;
        private final Map<Long, User> users = new HashMap<>();
        private final Map<Long, String> roomNames = new HashMap<>();

        Presenter(Reads r, CampfireApp app, FragmentCache fragments, Instant now, String requestHost) {
            this.r = r;
            this.app = app;
            this.fragments = fragments;
            this.richText = new RenderContext(new DbResolver(r, app.secrets, now), requestHost);
        }

        /** The message's cached fragment, or its render, stored for the next request. */
        Fragment fragment(PageMessage<Fragment> page) {
            if (page.getFragment() != null) {
                FRAGMENTS_PREFETCHED.increment();
                return page.getFragment();
            }
            Fragment[] rendered = new Fragment[1];
            Fragment fragment = fragments.fetch(messageKey(page.getKey()), Fragment.class, f -> f.getSize(), () -> {
                long start = System.nanoTime();
                rendered[0] = new Fragment(ViewsKt.renderMessage(view(page.getMessage())).getBytes(StandardCharsets.UTF_8));
                RENDER_NANOS.add(System.nanoTime() - start);
                return rendered[0];
            });
            (rendered[0] != null ? FRAGMENTS_RENDERED : FRAGMENTS_CACHED).increment();
            if (rendered[0] != null && rendered[0] != fragment) FRAGMENTS_RACED.increment();
            return fragment;
        }

        /** A message as {@code messages/_message} shows it: unrenderable when its creator is gone, or when its plain text raises. */
        private MessageView view(Message message) {
            String roomName = roomNames.computeIfAbsent(message.getRoomId(), id -> {
                Room room = r.room(id);
                if (room == null) throw new IllegalStateException("room " + id);
                return displayName(r, room, null);
            });
            User creator = user(message.getCreatorId());
            if (creator == null) return unrenderable(message, roomName);
            String body = orEmpty(r.richTextBody("Message", message.getId(), "body"));
            String plainText;
            try {
                plainText = RichText.INSTANCE.toPlainText(body, richText);
            } catch (RichTextError.Unrenderable e) {
                throw new IllegalStateException("message_tag's rescue raised logging " + e);
            } catch (RichTextError e) {
                return unrenderable(message, roomName);
            }
            if (r.hasAttachedBlob("Message", message.getId(), "attachment")) throw new Unsupported("attachments");
            if (plainText.startsWith("/play ")) throw new Unsupported("sounds");
            Presentation presented = RichText.INSTANCE.presentMessage(body, richText);
            if (!(presented instanceof Presentation.Html html)) return unrenderable(message, roomName);
            List<BoostView> boosts = new ArrayList<>();
            for (var b : r.boosts(message.getId())) {
                User booster = user(b.getBoosterId());
                if (booster == null) throw new Unsupported("a boost by a deleted user");
                boosts.add(new BoostView(b.getId(), b.getMessageId(), b.getContent(), ALL_EMOJI.matcher(b.getContent()).matches(), userView(app, booster)));
            }
            return new MessageView(message.getId(), message.getClientMessageId(), message.getRoomId(), roomName, userView(app, creator),
                    message.getCreatedAt(), message.getUpdatedAt(), ALL_EMOJI.matcher(plainText).matches(), html.getHtml(), boosts, false);
        }

        private MessageView unrenderable(Message message, String roomName) {
            return new MessageView(message.getId(), message.getClientMessageId(), message.getRoomId(), roomName,
                    new UserView(message.getCreatorId(), "", "", ""), message.getCreatedAt(), message.getUpdatedAt(), false, "", List.of(), true);
        }

        private User user(long id) {
            if (users.containsKey(id)) return users.get(id);
            User user = r.user(id);
            users.put(id, user);
            return user;
        }
    }

    /** Action Text's record lookups ({@code DbResolver}): mentions resolve to users through their signed GlobalIDs. */
    private record DbResolver(Reads r, Secrets secrets, Instant now) implements AttachableResolver {
        @Override
        public SignedLookup locateSigned(String sgid) {
            GlobalId gid = SignedGlobalId.INSTANCE.locateSigned(secrets, sgid, SignedGlobalId.ATTACHABLE_PURPOSE, now);
            if (gid == null) return SignedLookup.Invalid.INSTANCE;
            if (!gid.getModelName().equals("User")) return new SignedLookup.MissingRecord(gid.getModelName());
            User user = findUser(gid.getId());
            return user != null ? new SignedLookup.User(mentionUser(user)) : new SignedLookup.MissingRecord(gid.getModelName());
        }

        @Override
        public GidLookup findGid(String gid) {
            GlobalId parsed = GlobalId.Companion.parse(gid);
            if (parsed == null) return GidLookup.NotFound.INSTANCE;
            return switch (parsed.getModelName()) {
                case "User" -> {
                    User user = findUser(parsed.getId());
                    yield user != null ? new GidLookup.User(mentionUser(user)) : GidLookup.NotFound.INSTANCE;
                }
                case "Message", "Room", "Rooms::Open", "Rooms::Closed", "Rooms::Direct", "Boost", "Account" -> GidLookup.OtherModel.INSTANCE;
                default -> GidLookup.Raises.INSTANCE;
            };
        }

        /** {@code GlobalID::Locator} finds records by primary key; ids that aren't integers never match. */
        private User findUser(String id) {
            long value;
            try {
                value = Long.parseLong(id);
            } catch (NumberFormatException e) {
                return null;
            }
            return r.user(value);
        }

        private MentionUser mentionUser(User user) {
            return new MentionUser(user.getId(), user.getName(), user.getTitle(), SignedGlobalId.INSTANCE.attachableSgid(secrets, new GlobalId("User", user.getId())),
                    "/users/" + user.getId(), "/users/" + secrets.avatarToken(user.getId()) + "/avatar?v=" + user.getUpdatedAt().number());
        }
    }
}
