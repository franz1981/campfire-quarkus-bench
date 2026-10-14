package io.quarkus.campfire;

import java.util.List;
import java.util.function.Function;

import campfire.db.Boost;
import campfire.db.LayoutAccount;
import campfire.db.MessageKey;
import campfire.db.PageMessage;
import campfire.db.Room;
import campfire.db.Session;
import campfire.db.User;

/**
 * A request's reads from the session, user and room membership the SQLite stage read for it
 * ({@link SqliteStage.Prefetched}). Any other read, or one with other arguments, goes to
 * {@code others}: on a {@link RequestPool} thread the stage's forwarded reads; on an event loop none,
 * and the request is halted to run again on the pool ({@link Staged#needStage}).
 */
record PrefetchedReads(SqliteStage.Prefetched prefetched, Reads others) implements Reads {
    private <T> T other() {
        return Staged.needStage();
    }

    @Override
    public Session sessionByToken(String token) {
        if (token.equals(prefetched.token)) return prefetched.session;
        return others != null ? others.sessionByToken(token) : other();
    }

    @Override
    public User user(long id) {
        if (prefetched.session != null && prefetched.session.getUserId() == id) return prefetched.user;
        return others != null ? others.user(id) : other();
    }

    @Override
    public Room roomForUser(long userId, long roomId) {
        if (prefetched.user != null && prefetched.user.getId() == userId && prefetched.roomId == roomId) return prefetched.room;
        return others != null ? others.roomForUser(userId, roomId) : other();
    }

    @Override
    public boolean banned(String ipAddress) {
        return others != null ? others.banned(ipAddress) : other();
    }

    @Override
    public User authenticateBot(String botKey) {
        return others != null ? others.authenticateBot(botKey) : other();
    }

    @Override
    public Long originalRoomId() {
        return others != null ? others.originalRoomId() : other();
    }

    @Override
    public Long roomIdForUser(long userId, long roomId) {
        return others != null ? others.roomIdForUser(userId, roomId) : other();
    }

    @Override
    public Long firstRoomIdForUser(long userId) {
        return others != null ? others.firstRoomIdForUser(userId) : other();
    }

    @Override
    public String accountJoinCode() {
        return others != null ? others.accountJoinCode() : other();
    }

    @Override
    public LayoutAccount layoutAccount() {
        return others != null ? others.layoutAccount() : other();
    }

    @Override
    public Room room(long id) {
        return others != null ? others.room(id) : other();
    }

    @Override
    public List<User> roomUsers(long roomId) {
        return others != null ? others.roomUsers(roomId) : other();
    }

    @Override
    public <F> List<PageMessage<F>> lastPage(long roomId, Function<MessageKey, F> fragment) {
        return others != null ? others.lastPage(roomId, fragment) : other();
    }

    @Override
    public boolean paged(long roomId) {
        return others != null ? others.paged(roomId) : other();
    }

    @Override
    public String richTextBody(String recordType, long recordId, String name) {
        return others != null ? others.richTextBody(recordType, recordId, name) : other();
    }

    @Override
    public boolean hasAttachedBlob(String recordType, long recordId, String name) {
        return others != null ? others.hasAttachedBlob(recordType, recordId, name) : other();
    }

    @Override
    public List<Boost> boosts(long messageId) {
        return others != null ? others.boosts(messageId) : other();
    }
}
