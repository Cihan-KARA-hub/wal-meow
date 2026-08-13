package org.cihan.WalConsumer;

import org.cihan.model.WalEvent;
import org.cihan.parser.PgOutputParser;
import org.postgresql.PGConnection;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class WalConsumer {

    private static final Logger log = LoggerFactory.getLogger(WalConsumer.class);

    private static final String SLOT_NAME        = "wal_watcher_slot";
    private static final String PUBLICATION_NAME = "wal_watcher";
    private static final long   POLL_INTERVAL_MS = 10;

    private final WalConsumerConfig  config;
    private final PgOutputParser     parser;
    private final Consumer<WalEvent> eventHandler;

    private Connection          sqlConnection;
    private Connection          replConnection;
    private PGReplicationStream replicationStream;
    private Thread              walThread;
    private volatile boolean    running = false;

    public WalConsumer(WalConsumerConfig config, Consumer<WalEvent> eventHandler) {
        this.config       = config;
        this.eventHandler = eventHandler;
        this.parser       = new PgOutputParser();
    }


    public void start() throws SQLException {
        sqlConnection  = config.sqlConnection();
        replConnection = config.replicationConnection();


        ensurePublicationExists();
        createSlotIfNotExists();

        replicationStream = openReplicationStream();
        running = true;

        walThread = new Thread(this::streamLoop, "wal-stream-thread");
        walThread.setDaemon(true);
        walThread.start();

        log.info("WalConsumer started, WAL reading...");
        DatabaseMetaData meta = replConnection.getMetaData();
        System.out.println(meta.getURL());
        System.out.println(meta.getDriverVersion());
    }

    public void stop() {
        if (!running) return;
        running = false;

        closeQuietly(replicationStream);
        closeQuietly(replConnection);
        closeQuietly(sqlConnection);

        if (walThread != null && walThread.isAlive()) {
            walThread.interrupt();
            try { walThread.join(3_000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("WalConsumer stopped.");
    }


    private void streamLoop() {
        log.info("wal-stream-thread started, waiting for WAL...");

        while (running) {
            try {

                ByteBuffer msg = replicationStream.readPending();
                if (msg == null) {
                    TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
                    continue;
                }

                LogSequenceNumber lsn = replicationStream.getLastReceiveLSN();
                replicationStream.setAppliedLSN(lsn);
                replicationStream.setFlushedLSN(lsn);

                WalEvent event = parser.parse(msg, lsn.asString());

                if (event != null) {
                    eventHandler.accept(event);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running) log.error("WAL read error", e);
                break;
            }
        }

        log.info("wal-stream-thread terminated.");
    }

    // ─── Slot & Publication ───────────────────────────────────────────────────

    private void ensurePublicationExists() throws SQLException {
        try (Statement stmt = sqlConnection.createStatement()) {
            stmt.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_publication WHERE pubname = 'wal_watcher'
                    ) THEN
                        CREATE PUBLICATION wal_watcher FOR ALL TABLES;
                    END IF;
                END $$;
            """);
        }
    }

    private void createSlotIfNotExists() throws SQLException {
        boolean slotExists;

        try (Statement stmt = sqlConnection.createStatement()) {
            var rs = stmt.executeQuery(
                    "SELECT slot_name FROM pg_replication_slots WHERE slot_name = '" + SLOT_NAME + "'"
            );
            slotExists = rs.next();
        }

        if (!slotExists) {
            PGConnection pgConn = replConnection.unwrap(PGConnection.class);
            pgConn.getReplicationAPI()
                    .createReplicationSlot()
                    .logical()
                    .withSlotName(SLOT_NAME)
                    .withOutputPlugin("pgoutput")
                            .make();
            log.info("Replication slot created: {}", SLOT_NAME);
        } else {
            log.info("Using existing replication slot: {}", SLOT_NAME);
        }
    }

    private PGReplicationStream openReplicationStream() throws SQLException {
        PGConnection pgConn = replConnection.unwrap(PGConnection.class);
        return pgConn.getReplicationAPI()
                .replicationStream()
                .logical()
                .withSlotName(SLOT_NAME)
                .withSlotOption("proto_version",     "1")
                .withSlotOption("publication_names", PUBLICATION_NAME)
                .withStatusInterval(10, TimeUnit.SECONDS)
                .start();
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private void closeQuietly(AutoCloseable c) {
        if (c != null) try { c.close(); } catch (Exception e) { log.debug("Error while closing", e); }
    }
}