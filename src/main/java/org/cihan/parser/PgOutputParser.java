package org.cihan.parser;

import org.cihan.model.RelationInfo;
import org.cihan.model.WalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * pgoutput message type (first byte = type identifier):
 *  'B' = BEGIN
 *  'C' = COMMIT
 *  'I' = INSERT
 *  'U' = UPDATE
 *  'D' = DELETE
 *  'R' = RELATION
 *  'T' = TRUNCATE
 *  'Y' = TYPE
 *  'O' = ORIGIN
 */
public class PgOutputParser {

    private static final Logger log = LoggerFactory.getLogger(PgOutputParser.class);

    private static final long PG_EPOCH_MICROS =
            LocalDateTime.of(2000, 1, 1, 0, 0, 0)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli() * 1000;

    private final Map<Integer, RelationInfo> relationCache = new HashMap<>();

    private Long currentTxId;
    private OffsetDateTime currentTxTimestamp;


    public WalEvent parse(ByteBuffer buffer, String lsn) {
        if (buffer == null || !buffer.hasRemaining()) return null;

        byte msgType = buffer.get();

        try {
            return switch ((char) msgType) {
                case 'R' -> { parseRelation(buffer); yield null; }
                case 'B' -> parseBegin(buffer, lsn);
                case 'C' -> parseCommit(buffer, lsn);
                case 'I' -> parseInsert(buffer, lsn);
                case 'U' -> parseUpdate(buffer, lsn);
                case 'D' -> parseDelete(buffer, lsn);
                default  -> {
                    log.trace("not found message type: {}", (char) msgType);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.error("Parse error (msgType={}): {}", (char) msgType, e.getMessage(), e);
            return null;
        }
    }

    // ─── Message Parsers ──────────────────────────────────────────────────────

    /**
     * RELATION message: table schema info writing in cache .
     * Format: [OID:int32][namespace:str][name:str][replicaIdentity:int8][columnCount:int16]
     *         for every column : [flags:int8][name:str][typeOID:int32][typeModifier:int32]
     */
    private void parseRelation(ByteBuffer buf) {
        int oid        = buf.getInt();
        String schema  = readString(buf);
        String table   = readString(buf);
        buf.get(); // replicaIdentity (f=full, d=default, n=nothing, i=index)

        int columnCount = buf.getShort();
        String[] columnNames = new String[columnCount];

        for (int i = 0; i < columnCount; i++) {
            buf.get();        // flags (1 = part of key)
            columnNames[i] = readString(buf);
            buf.getInt();     // type OID
            buf.getInt();     // type modifier
        }

        relationCache.put(oid, new RelationInfo(oid, schema, table, columnNames));
        log.debug("Relation caching: {}.{} ({} column)", schema, table, columnCount);
    }

    private WalEvent parseBegin(ByteBuffer buf, String lsn) {
        buf.getLong();  // final LSN of transaction
        long timestampMicros = buf.getLong();
        currentTxId = (long) buf.getInt() & 0xFFFFFFFFL;
        currentTxTimestamp = microsToInstant(timestampMicros);

        return WalEvent.builder()
                .lsn(lsn)
                .operation(WalEvent.Operation.BEGIN)
                .timestamp(currentTxTimestamp)
                .transactionId(currentTxId)
                .build();
    }

    private WalEvent parseCommit(ByteBuffer buf, String lsn) {
        buf.get();       // flags (reserved)
        buf.getLong();   // commit LSN
        buf.getLong();   // end LSN
        long timestampMicros = buf.getLong();

        return WalEvent.builder()
                .lsn(lsn)
                .operation(WalEvent.Operation.COMMIT)
                .timestamp(microsToInstant(timestampMicros))
                .transactionId(currentTxId)
                .build();
    }

    private WalEvent parseInsert(ByteBuffer buf, String lsn) {
        int relationOid = buf.getInt();
        buf.get(); // 'N' = new tuple

        RelationInfo rel = getRelation(relationOid);
        Map<String, Object> newValues = parseTupleData(buf, rel);

        return WalEvent.builder()
                .lsn(lsn)
                .operation(WalEvent.Operation.INSERT)
                .timestamp(currentTxTimestamp)
                .transactionId(currentTxId)
                .schema(rel.schema())
                .table(rel.table())
                .column(newValues)
                .build();
    }

    private WalEvent parseUpdate(ByteBuffer buf, String lsn) {
        int relationOid = buf.getInt();
        RelationInfo rel = getRelation(relationOid);

        Map<String, Object> oldValues = null;
        byte indicator = (byte) buf.get(buf.position()); // peek

        // 'O' = old values (REPLICA IDENTITY FULL), 'K' = key values
        if (indicator == 'O' || indicator == 'K') {
            buf.get(); // consume indicator
            oldValues = parseTupleData(buf, rel);
        }

        buf.get(); // 'N' = new tuple
        Map<String, Object> newValues = parseTupleData(buf, rel);

        return WalEvent.builder()
                .lsn(lsn)
                .operation(WalEvent.Operation.UPDATE)
                .timestamp(currentTxTimestamp)
                .transactionId(currentTxId)
                .schema(rel.schema())
                .table(rel.table())
                .column(newValues)
                .oldData(oldValues)
                .build();
    }

    private WalEvent parseDelete(ByteBuffer buf, String lsn) {
        int relationOid = buf.getInt();
        RelationInfo rel = getRelation(relationOid);

        byte indicator = buf.get(); // 'O' = full old, 'K' = key only
        Map<String, Object> oldValues = parseTupleData(buf, rel);

        return WalEvent.builder()
                .lsn(lsn)
                .operation(WalEvent.Operation.DELETE)
                .timestamp(currentTxTimestamp)
                .transactionId(currentTxId)
                .schema(rel.schema())
                .table(rel.table())
                .oldData(oldValues)
                .build();
    }

    // ─── Tuple Data Parser ────────────────────────────────────────────────────

    private Map<String, Object> parseTupleData(ByteBuffer buf, RelationInfo rel) {
        int columnCount = buf.getShort();
        Map<String, Object> row = new HashMap<>(columnCount);

        for (int i = 0; i < columnCount; i++) {
            String colName = i < rel.columns().length ? rel.columns()[i] : "col_" + i;
            byte colType = buf.get();

            switch (colType) {
                case 't' -> {
                    int len = buf.getInt();
                    byte[] bytes = new byte[len];
                    buf.get(bytes);
                    row.put(colName, new String(bytes, StandardCharsets.UTF_8));
                }
                case 'n' -> row.put(colName, null);
                case 'u' -> row.put(colName, "<unchanged>");
                default  -> log.warn("Unknown column type: {}", (char) colType);
            }
        }
        return row;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String readString(ByteBuffer buf) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte b;
        while ((b = buf.get()) != 0) {
            bytes.write(b);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private OffsetDateTime microsToInstant(long pgMicros) {
        long unixMicros = pgMicros + PG_EPOCH_MICROS;
        Instant instant = Instant.ofEpochSecond(unixMicros / 1_000_000, (unixMicros % 1_000_000) * 1000);
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private RelationInfo getRelation(int oid) {
        RelationInfo rel = relationCache.get(oid);
        if (rel == null) {
            log.warn("Relation not found  OID={}, empty relation ", oid);
            return new RelationInfo(oid, "unknown", "unknown_" + oid, new String[0]);
        }
        return rel;
    }
}
