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
 * Reads for a thread that may wait ({@link RequestPool}): each one runs on the {@link SqliteStage}
 * and only the SQL runs there (for {@link #lastPage}, also {@code fragment}, a fragment cache lookup).
 */
record ForwardingReads(SqliteStage stage) implements Reads {
    @Override
    public boolean banned(String ipAddress) {
        return stage.read(r -> r.banned(ipAddress));
    }

    @Override
    public User authenticateBot(String botKey) {
        return stage.read(r -> r.authenticateBot(botKey));
    }

    @Override
    public Session sessionByToken(String token) {
        return stage.read(r -> r.sessionByToken(token));
    }

    @Override
    public User user(long id) {
        return stage.read(r -> r.user(id));
    }

    @Override
    public Room roomForUser(long userId, long roomId) {
        return stage.read(r -> r.roomForUser(userId, roomId));
    }

    @Override
    public Long originalRoomId() {
        return stage.read(Reads::originalRoomId);
    }

    @Override
    public Long roomIdForUser(long userId, long roomId) {
        return stage.read(r -> r.roomIdForUser(userId, roomId));
    }

    @Override
    public Long firstRoomIdForUser(long userId) {
        return stage.read(r -> r.firstRoomIdForUser(userId));
    }

    @Override
    public String accountJoinCode() {
        return stage.read(Reads::accountJoinCode);
    }

    @Override
    public LayoutAccount layoutAccount() {
        return stage.read(Reads::layoutAccount);
    }

    @Override
    public Room room(long id) {
        return stage.read(r -> r.room(id));
    }

    @Override
    public List<User> roomUsers(long roomId) {
        return stage.read(r -> r.roomUsers(roomId));
    }

    @Override
    public <F> List<PageMessage<F>> lastPage(long roomId, Function<MessageKey, F> fragment) {
        return stage.read(r -> r.lastPage(roomId, fragment));
    }

    @Override
    public boolean paged(long roomId) {
        return stage.read(r -> r.paged(roomId));
    }

    @Override
    public String richTextBody(String recordType, long recordId, String name) {
        return stage.read(r -> r.richTextBody(recordType, recordId, name));
    }

    @Override
    public boolean hasAttachedBlob(String recordType, long recordId, String name) {
        return stage.read(r -> r.hasAttachedBlob(recordType, recordId, name));
    }

    @Override
    public List<Boost> boosts(long messageId) {
        return stage.read(r -> r.boosts(messageId));
    }
}
