package io.github.revsnai.ffmsqlite.db;

import java.lang.foreign.MemorySegment;

/** The checked exception thrown by every database operation, in the role of {@code java.sql.SQLException}. */
public class SQLiteException extends Exception {
    private final int errorCode;
    private final int extendedErrorCode;

    public SQLiteException(String message) {
        this(message, 0, 0);
    }

    public SQLiteException(String message, int errorCode, int extendedErrorCode) {
        super(message);
        this.errorCode = errorCode;
        this.extendedErrorCode = extendedErrorCode;
    }

    /** The primary SQLite result code, e.g. 19 (SQLITE_CONSTRAINT); 0 for errors raised by this library. */
    public int getErrorCode() {
        return errorCode;
    }

    /** The extended result code, e.g. 2067 (SQLITE_CONSTRAINT_UNIQUE). */
    public int getExtendedErrorCode() {
        return extendedErrorCode;
    }

    static SQLiteException of(MemorySegment db, int rc, String context) {
        int extended = db.equals(MemorySegment.NULL) ? rc : Native.extended_errcode(db);
        String msg = db.equals(MemorySegment.NULL) ? Native.errstr(rc) : Native.errmsg(db);
        return new SQLiteException("[" + Native.errstr(rc & 0xff) + "] " + msg + " (" + context + ")", rc & 0xff, extended);
    }
}
