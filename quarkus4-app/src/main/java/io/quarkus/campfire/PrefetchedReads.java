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
 * A request's reads on an event loop: the session, user and room membership the SQLite stage read
 * for it ({@link SqliteStage.Prefetched}). Any other read, or one with other arguments, needs SQLite:
 * the request is halted and runs again on the stage ({@link Staged#needStage}).
 */
record PrefetchedReads(SqliteStage.Prefetched prefetched) implements Reads {
    @Override
    public Session sessionByToken(String token) {
        return token.equals(prefetched.token) ? prefetched.session : Staged.needStage();
    }

    @Override
    public User user(long id) {
        return prefetched.session != null && prefetched.session.getUserId() == id ? prefetched.user : Staged.needStage();
    }

    @Override
    public Room roomForUser(long userId, long roomId) {
        return prefetched.user != null && prefetched.user.getId() == userId && prefetched.roomId == roomId ? prefetched.room : Staged.needStage();
    }

    @Override
    public boolean banned(String ipAddress) {
        return Staged.needStage();
    }

    @Override
    public User authenticateBot(String botKey) {
        return Staged.needStage();
    }

    @Override
    public Long originalRoomId() {
        return Staged.needStage();
    }

    @Override
    public Long roomIdForUser(long userId, long roomId) {
        return Staged.needStage();
    }

    @Override
    public Long firstRoomIdForUser(long userId) {
        return Staged.needStage();
    }

    @Override
    public String accountJoinCode() {
        return Staged.needStage();
    }

    @Override
    public LayoutAccount layoutAccount() {
        return Staged.needStage();
    }

    @Override
    public Room room(long id) {
        return Staged.needStage();
    }

    @Override
    public List<User> roomUsers(long roomId) {
        return Staged.needStage();
    }

    @Override
    public <F> List<PageMessage<F>> lastPage(long roomId, Function<MessageKey, F> fragment) {
        return Staged.needStage();
    }

    @Override
    public boolean paged(long roomId) {
        return Staged.needStage();
    }

    @Override
    public String richTextBody(String recordType, long recordId, String name) {
        return Staged.needStage();
    }

    @Override
    public boolean hasAttachedBlob(String recordType, long recordId, String name) {
        return Staged.needStage();
    }

    @Override
    public List<Boost> boosts(long messageId) {
        return Staged.needStage();
    }
}
