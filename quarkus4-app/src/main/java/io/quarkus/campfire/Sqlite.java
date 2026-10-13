package io.quarkus.campfire;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SQLite's C API through the Java FFM API: a Java port of the Kotlin port's bindings
 * ({@code db/src/main/kotlin/campfire/db/Sqlite.kt} in
 * <a href="https://github.com/mysticcoders/once-campfire-kotlin">once-campfire-kotlin</a> at 87436f3,
 * MIT license, Copyright (c) 37signals, LLC), against the same library: {@code libcampfire_sqlite3},
 * the Rust port's SQLite amalgamation built with its flags (that port's {@code native/build.sh}).
 *
 * <p>Binds and column reads are {@code critical} downcalls, which skip the thread-state transition
 * and may read Java arrays in place; they only copy memory. Steps, resets and opens are ordinary
 * downcalls: they can do I/O or wait out the busy timeout. Statements are prepared with
 * {@code SQLITE_PREPARE_PERSISTENT} and live as long as their connection. A connection is opened
 * {@code NOMUTEX}: one thread at a time.
 */
final class Sqlite {
    private static final int OK = 0;
    private static final int ROW = 100;
    private static final int DONE = 101;
    private static final int OPEN_READONLY = 0x1;
    private static final int OPEN_READWRITE = 0x2;
    private static final int OPEN_CREATE = 0x4;
    private static final int OPEN_URI = 0x40;
    private static final int OPEN_NOMUTEX = 0x8000;
    private static final int PREPARE_PERSISTENT = 0x1;
    private static final MemorySegment TRANSIENT = MemorySegment.ofAddress(-1);
    /** All of memory as one segment, so text is copied from SQLite's pointer without a segment per read. */
    private static final MemorySegment ALL_MEMORY = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

    private final MethodHandle open;
    private final MethodHandle close;
    private final MethodHandle prepare;
    private final MethodHandle exec;
    private final MethodHandle errmsg;
    private final MethodHandle bindInt64;
    private final MethodHandle bindText;
    private final MethodHandle bindNull;
    private final MethodHandle step;
    private final MethodHandle columnInt64;
    private final MethodHandle columnText;
    private final MethodHandle columnBytes;
    private final MethodHandle reset;
    private final MethodHandle clearBindings;

    /** Binds the downcalls against {@code library} ({@code libcampfire_sqlite3.so}). */
    Sqlite(Path library) {
        if (!Files.exists(library)) throw new IllegalStateException("no SQLite library at " + library + " (campfire.sqlite-lib, CAMPFIRE_SQLITE_LIB)");
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.libraryLookup(library, Arena.global());
        Linker.Option critical = Linker.Option.critical(true);
        open = handle(linker, lookup, "sqlite3_open_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        close = handle(linker, lookup, "sqlite3_close_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        prepare = handle(linker, lookup, "sqlite3_prepare_v3", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        exec = handle(linker, lookup, "sqlite3_exec", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        errmsg = handle(linker, lookup, "sqlite3_errmsg", FunctionDescriptor.of(ADDRESS, ADDRESS));
        bindInt64 = handle(linker, lookup, "sqlite3_bind_int64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG), critical);
        bindText = handle(linker, lookup, "sqlite3_bind_text", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), critical);
        bindNull = handle(linker, lookup, "sqlite3_bind_null", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), critical);
        step = handle(linker, lookup, "sqlite3_step", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        columnInt64 = handle(linker, lookup, "sqlite3_column_int64", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT), critical);
        columnText = handle(linker, lookup, "sqlite3_column_text", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT), critical);
        columnBytes = handle(linker, lookup, "sqlite3_column_bytes", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), critical);
        reset = handle(linker, lookup, "sqlite3_reset", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        clearBindings = handle(linker, lookup, "sqlite3_clear_bindings", FunctionDescriptor.of(JAVA_INT, ADDRESS), critical);
    }

    private static MethodHandle handle(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor, Linker.Option... options) {
        return linker.downcallHandle(lookup.find(name).orElseThrow(() -> new IllegalStateException("missing " + name)), descriptor, options);
    }

    /** Opens {@code path} read-write (creating it) or, {@code readOnly}, read-only; always {@code URI|NOMUTEX}, as Rust and Kotlin open it. */
    Connection open(String path, boolean readOnly) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int flags = (readOnly ? OPEN_READONLY : OPEN_READWRITE | OPEN_CREATE) | OPEN_NOMUTEX | OPEN_URI;
            int rc = (int) open.invokeExact(arena.allocateFrom(path), out, flags, MemorySegment.NULL);
            Connection connection = new Connection(out.get(ADDRESS, 0));
            if (rc != OK) throw new IllegalStateException("sqlite3_open_v2(" + path + "): " + connection.errmsg());
            return connection;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** One SQLite connection; used by one thread at a time. */
    final class Connection {
        private final MemorySegment db;

        private Connection(MemorySegment db) {
            this.db = db;
        }

        Statement prepare(String sql) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(ADDRESS);
                int rc = (int) prepare.invokeExact(db, arena.allocateFrom(sql), -1, PREPARE_PERSISTENT, out, MemorySegment.NULL);
                if (rc != OK) throw new IllegalStateException("sqlite3_prepare_v3: " + errmsg() + ": " + sql);
                return new Statement(this, out.get(ADDRESS, 0));
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        void execute(String sql) {
            try (Arena arena = Arena.ofConfined()) {
                int rc = (int) exec.invokeExact(db, arena.allocateFrom(sql), MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
                if (rc != OK) throw new IllegalStateException("sqlite3_exec: " + errmsg() + ": " + sql);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        /** Closes the connection; its statements are finalized with it ({@code sqlite3_close_v2}). */
        void close() {
            try {
                int rc = (int) close.invokeExact(db);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        String errmsg() {
            try {
                return ((MemorySegment) errmsg.invokeExact(db)).reinterpret(Long.MAX_VALUE).getString(0);
            } catch (Throwable e) {
                return "(no message: " + e + ")";
            }
        }
    }

    /** A prepared statement: bind, step through rows, read columns (1-based, as in JDBC), reset. */
    final class Statement {
        private final Connection connection;
        private final MemorySegment stmt;
        private byte[] scratch = new byte[256];

        private Statement(Connection connection, MemorySegment stmt) {
            this.connection = connection;
            this.stmt = stmt;
        }

        void bindLong(int index, long value) {
            int rc;
            try {
                rc = (int) bindInt64.invokeExact(stmt, index, value);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
            if (rc != OK) throw new IllegalStateException(connection.errmsg());
        }

        /** Binds {@code value}, or SQL NULL when it's null (as JDBC's {@code setString} does). */
        void bindText(int index, String value) {
            if (value == null) {
                bindNull(index);
                return;
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            int rc;
            try {
                rc = (int) bindText.invokeExact(stmt, index, MemorySegment.ofArray(bytes), bytes.length, TRANSIENT);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
            if (rc != OK) throw new IllegalStateException(connection.errmsg());
        }

        void bindNull(int index) {
            int rc;
            try {
                rc = (int) bindNull.invokeExact(stmt, index);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
            if (rc != OK) throw new IllegalStateException(connection.errmsg());
        }

        /** Advances to the next row (running the statement first, if it hasn't run since a reset). */
        boolean step() {
            int rc;
            try {
                rc = (int) step.invokeExact(stmt);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
            if (rc == ROW) return true;
            if (rc == DONE) return false;
            throw new IllegalStateException("sqlite3_step: " + rc + " " + connection.errmsg());
        }

        long integer(int column) {
            try {
                return (long) columnInt64.invokeExact(stmt, column - 1);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        /** The column's text, or null for SQL NULL. */
        String text(int column) {
            try {
                long address = (long) columnText.invokeExact(stmt, column - 1);
                if (address == 0L) return null;
                int n = (int) columnBytes.invokeExact(stmt, column - 1);
                if (n > scratch.length) scratch = new byte[Math.max(n, scratch.length * 2)];
                MemorySegment.copy(ALL_MEMORY, JAVA_BYTE, address, scratch, 0, n);
                return new String(scratch, 0, n, StandardCharsets.UTF_8);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        void reset() {
            try {
                int r = (int) reset.invokeExact(stmt);
                int c = (int) clearBindings.invokeExact(stmt);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
