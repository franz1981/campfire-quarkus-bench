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
 * The room page's reads ({@code campfire.db.Reader} in the Kotlin port): {@link Db.Reader} runs them
 * on the SQLite stage's connection; {@link PrefetchedReads} answers a request's authentication reads
 * on an event loop from what the stage read for it.
 */
public interface Reads {
    boolean banned(String ipAddress);

    User authenticateBot(String botKey);

    Session sessionByToken(String token);

    User user(long id);

    Room roomForUser(long userId, long roomId);

    Long originalRoomId();

    Long roomIdForUser(long userId, long roomId);

    Long firstRoomIdForUser(long userId);

    String accountJoinCode();

    LayoutAccount layoutAccount();

    Room room(long id);

    List<User> roomUsers(long roomId);

    <F> List<PageMessage<F>> lastPage(long roomId, Function<MessageKey, F> fragment);

    boolean paged(long roomId);

    String richTextBody(String recordType, long recordId, String name);

    boolean hasAttachedBlob(String recordType, long recordId, String name);

    List<Boost> boosts(long messageId);
}
