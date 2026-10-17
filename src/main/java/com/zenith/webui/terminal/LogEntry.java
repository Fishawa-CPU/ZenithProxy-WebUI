package com.zenith.webui.terminal;

/**
 * One log line kept in the ring buffer and streamed to the terminal.
 * Serialized by Gson — field names are the frontend contract (app.js).
 */
public final class LogEntry {
    public final String level;
    public final String logger;
    public final String message;
    public final long timestamp;
    public final long id;

    public LogEntry(String level, String logger, String message, long timestamp, long id) {
        // Coerce nulls: Gson omits null fields by default, which would break the
        // frontend contract (it calls log.level.toLowerCase() unconditionally).
        this.level = level != null ? level : "INFO";
        this.logger = logger != null ? logger : "webui";
        this.message = message != null ? message : "";
        this.timestamp = timestamp;
        this.id = id;
    }
}
