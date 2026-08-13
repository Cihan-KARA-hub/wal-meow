package org.cihan.model;


import java.time.OffsetDateTime;
import java.util.Map;

public class WalEvent {

    public enum Operation {
        INSERT, UPDATE, DELETE, BEGIN, COMMIT, UNKNOWN
    }

    private String              lsn;
    private OffsetDateTime      timestamp;
    private Operation           operation;
    private String              table;
    private String              schema;
    private Map<String, Object> column;
    private Map<String, Object> oldData;
    private Long                transactionId;

    public WalEvent() {}

    // ─── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final WalEvent e = new WalEvent();

        public Builder lsn(String lsn)                   { e.lsn = lsn;           return this; }
        public Builder timestamp(OffsetDateTime ts)       { e.timestamp = ts;       return this; }
        public Builder operation(Operation op)            { e.operation = op;       return this; }
        public Builder table(String table)                { e.table = table;        return this; }
        public Builder schema(String schema)              { e.schema = schema;      return this; }
        public Builder column(Map<String, Object> col)    { e.column = col;         return this; }
        public Builder oldData(Map<String, Object> old)   { e.oldData = old;        return this; }
        public Builder transactionId(Long txId)           { e.transactionId = txId; return this; }

        public WalEvent build() { return e; }
    }

    // ─── Getters / Setters ────────────────────────────────────────────────────

    public String getLsn()                             { return lsn; }
    public void setLsn(String lsn)                     { this.lsn = lsn; }

    public OffsetDateTime getTimestamp()               { return timestamp; }
    public void setTimestamp(OffsetDateTime timestamp) { this.timestamp = timestamp; }

    public Operation getOperation()                    { return operation; }
    public void setOperation(Operation operation)      { this.operation = operation; }

    public String getTable()                           { return table; }
    public void setTable(String table)                 { this.table = table; }

    public String getSchema()                          { return schema; }
    public void setSchema(String schema)               { this.schema = schema; }

    public Map<String, Object> getColumn()             { return column; }
    public void setColumn(Map<String, Object> column)  { this.column = column; }

    public Map<String, Object> getOldData()            { return oldData; }
    public void setOldData(Map<String, Object> old)    { this.oldData = old; }

    public Long getTransactionId()                     { return transactionId; }
    public void setTransactionId(Long txId)            { this.transactionId = txId; }

    @Override
    public String toString() {
        return String.format("[%s] %s %s.%s lsn=%s txId=%d",
                timestamp, operation, schema, table, lsn, transactionId);
    }
}