package io.quarkus.campfire;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import campfire.db.Boost;
import campfire.db.LayoutAccount;
import campfire.db.Message;
import campfire.db.MessageKey;
import campfire.db.PageMessage;
import campfire.db.Room;
import campfire.db.RoomKind;
import campfire.db.Session;
import campfire.db.User;

/**
 * The room page's reads, all done at once on the {@link SqliteStage} ({@link #load}, one forwarded
 * read: one read transaction), then answered from memory while the page renders on a
 * {@link RequestPool} thread. The page reads, as {@code RoomsController.renderShow}, its
 * {@code Presenter} and {@code layout} do: the last page of messages and, for each message without a
 * kept fragment, its room, creator, rich text body, attachment and boosts with their boosters; the
 * original room, whether the room is paged, the join code, the account and its logo, the last room
 * visited. A read with other arguments (a mention's user, a fragment evicted since) goes to
 * {@code others}.
 */
final class RoomPageReads implements Reads {
    private final Map<Key, Object> answers = new HashMap<>();
    private Reads others;

    private record Key(String read, Object[] args) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && read.equals(k.read) && Arrays.equals(args, k.args);
        }

        @Override
        public int hashCode() {
            return 31 * read.hashCode() + Arrays.hashCode(args);
        }
    }

    private RoomPageReads() {
    }

    /**
     * Runs on the stage: the page's reads through {@code r}, the page's fragments looked up with
     * {@code fragment} (which messages need rendering decides which rows are read).
     */
    static RoomPageReads load(Reads r, Room room, long userId, Long lastRoom, Function<MessageKey, ?> fragment) {
        RoomPageReads page = new RoomPageReads();
        List<? extends PageMessage<?>> messages = r.lastPage(room.getId(), fragment::apply);
        page.keep("lastPage", messages, room.getId());
        for (PageMessage<?> message : messages) {
            Message m = message.getMessage();
            if (m == null) continue;
            Room messageRoom = page.room(r, m.getRoomId());
            if (messageRoom != null && messageRoom.getKind() == RoomKind.Direct) page.keep("roomUsers", r.roomUsers(messageRoom.getId()), messageRoom.getId());
            page.user(r, m.getCreatorId());
            page.keep("richTextBody", r.richTextBody("Message", m.getId(), "body"), "Message", m.getId(), "body");
            page.keep("hasAttachedBlob", r.hasAttachedBlob("Message", m.getId(), "attachment"), "Message", m.getId(), "attachment");
            List<Boost> boosts = r.boosts(m.getId());
            page.keep("boosts", boosts, m.getId());
            for (Boost boost : boosts) page.user(r, boost.getBoosterId());
        }
        page.keep("originalRoomId", r.originalRoomId());
        page.keep("paged", r.paged(room.getId()), room.getId());
        page.keep("accountJoinCode", r.accountJoinCode());
        if (room.getKind() == RoomKind.Direct && !page.has("roomUsers", room.getId())) page.keep("roomUsers", r.roomUsers(room.getId()), room.getId());
        LayoutAccount account = r.layoutAccount();
        page.keep("layoutAccount", account);
        if (account != null) page.keep("hasAttachedBlob", r.hasAttachedBlob("Account", account.getId(), "logo"), "Account", account.getId(), "logo");
        Long visited = null;
        if (lastRoom != null) {
            visited = r.roomIdForUser(userId, lastRoom);
            page.keep("roomIdForUser", visited, userId, lastRoom);
        }
        if (visited == null) page.keep("firstRoomIdForUser", r.firstRoomIdForUser(userId), userId);
        return page;
    }

    /** These reads, any other one from {@code others}. */
    RoomPageReads with(Reads others) {
        this.others = others;
        return this;
    }

    private void keep(String read, Object answer, Object... args) {
        answers.put(new Key(read, args), answer);
    }

    private boolean has(String read, Object... args) {
        return answers.containsKey(new Key(read, args));
    }

    private Room room(Reads r, long id) {
        Key key = new Key("room", new Object[] {id});
        if (!answers.containsKey(key)) answers.put(key, r.room(id));
        return (Room) answers.get(key);
    }

    private void user(Reads r, long id) {
        Key key = new Key("user", new Object[] {id});
        if (!answers.containsKey(key)) answers.put(key, r.user(id));
    }

    /** The kept answer to {@code read(args)}, or null with {@code found[0]} false. */
    @SuppressWarnings("unchecked")
    private <T> T kept(boolean[] found, String read, Object... args) {
        Key key = new Key(read, args);
        found[0] = answers.containsKey(key);
        return (T) answers.get(key);
    }

    @Override
    public boolean banned(String ipAddress) {
        return others.banned(ipAddress);
    }

    @Override
    public User authenticateBot(String botKey) {
        return others.authenticateBot(botKey);
    }

    @Override
    public Session sessionByToken(String token) {
        return others.sessionByToken(token);
    }

    @Override
    public User user(long id) {
        boolean[] found = new boolean[1];
        User user = kept(found, "user", id);
        return found[0] ? user : others.user(id);
    }

    @Override
    public Room roomForUser(long userId, long roomId) {
        return others.roomForUser(userId, roomId);
    }

    @Override
    public Long originalRoomId() {
        boolean[] found = new boolean[1];
        Long id = kept(found, "originalRoomId");
        return found[0] ? id : others.originalRoomId();
    }

    @Override
    public Long roomIdForUser(long userId, long roomId) {
        boolean[] found = new boolean[1];
        Long id = kept(found, "roomIdForUser", userId, roomId);
        return found[0] ? id : others.roomIdForUser(userId, roomId);
    }

    @Override
    public Long firstRoomIdForUser(long userId) {
        boolean[] found = new boolean[1];
        Long id = kept(found, "firstRoomIdForUser", userId);
        return found[0] ? id : others.firstRoomIdForUser(userId);
    }

    @Override
    public String accountJoinCode() {
        boolean[] found = new boolean[1];
        String code = kept(found, "accountJoinCode");
        return found[0] ? code : others.accountJoinCode();
    }

    @Override
    public LayoutAccount layoutAccount() {
        boolean[] found = new boolean[1];
        LayoutAccount account = kept(found, "layoutAccount");
        return found[0] ? account : others.layoutAccount();
    }

    @Override
    public Room room(long id) {
        boolean[] found = new boolean[1];
        Room room = kept(found, "room", id);
        return found[0] ? room : others.room(id);
    }

    @Override
    public List<User> roomUsers(long roomId) {
        boolean[] found = new boolean[1];
        List<User> users = kept(found, "roomUsers", roomId);
        return found[0] ? users : others.roomUsers(roomId);
    }

    /**
     * The page as the stage read it, with the fragments it found then (each held, so a fragment
     * evicted since is still the one this page renders); a page of another room is read again.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <F> List<PageMessage<F>> lastPage(long roomId, Function<MessageKey, F> fragment) {
        boolean[] found = new boolean[1];
        List<PageMessage<F>> page = kept(found, "lastPage", roomId);
        return found[0] ? page : others.lastPage(roomId, fragment);
    }

    @Override
    public boolean paged(long roomId) {
        boolean[] found = new boolean[1];
        Boolean paged = kept(found, "paged", roomId);
        return found[0] ? paged : others.paged(roomId);
    }

    @Override
    public String richTextBody(String recordType, long recordId, String name) {
        boolean[] found = new boolean[1];
        String body = kept(found, "richTextBody", recordType, recordId, name);
        return found[0] ? body : others.richTextBody(recordType, recordId, name);
    }

    @Override
    public boolean hasAttachedBlob(String recordType, long recordId, String name) {
        boolean[] found = new boolean[1];
        Boolean attached = kept(found, "hasAttachedBlob", recordType, recordId, name);
        return found[0] ? attached : others.hasAttachedBlob(recordType, recordId, name);
    }

    @Override
    public List<Boost> boosts(long messageId) {
        boolean[] found = new boolean[1];
        List<Boost> boosts = kept(found, "boosts", messageId);
        return found[0] ? boosts : others.boosts(messageId);
    }
}
