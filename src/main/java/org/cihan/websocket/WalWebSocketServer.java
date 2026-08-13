package org.cihan.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.cihan.model.WalEvent;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WAL olaylarını canlı olarak izlemek için basit bir web arayüzü sunar:
 *  - "/"   → src/main/resources/static/index.html (dashboard)
 *  - "/ws" → her yeni WalEvent'in JSON olarak yayınlandığı WebSocket ucu
 */
public class WalWebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(WalWebSocketServer.class);
    private static final Set<Session> SESSIONS = ConcurrentHashMap.newKeySet();
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final Duration IDLE_TIMEOUT  = Duration.ofMinutes(10);
    private static final long     PING_INTERVAL_SECONDS = 20;

    private final int port;
    private Server server;
    private ScheduledExecutorService pingScheduler;

    public WalWebSocketServer(int port) {
        this.port = port;
    }

    public void start() throws Exception {
        server = new Server(port);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, container) -> {
            container.setIdleTimeout(IDLE_TIMEOUT);
            container.addMapping("/ws", WalEventEndpoint.class);
        });

        context.addServlet(new ServletHolder(new IndexServlet()), "/");

        server.start();
        log.info("Dashboard ready: http://localhost:{}/", port);

        // WAL olayları seyrek geldiğinde bağlantı boşta kalıp zaman aşımına
        // uğramasın diye periyodik ping gönderiyoruz.
        pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-ping");
            t.setDaemon(true);
            return t;
        });
        pingScheduler.scheduleAtFixedRate(this::pingAll, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() throws Exception {
        if (pingScheduler != null) {
            pingScheduler.shutdownNow();
        }
        if (server != null) {
            server.stop();
        }
    }

    private void pingAll() {
        for (Session session : SESSIONS) {
            if (!session.isOpen()) continue;
            try {
                session.getRemote().sendPing(ByteBuffer.allocate(0));
            } catch (IOException e) {
                log.debug("Failed to send ping, closing connection", e);
                SESSIONS.remove(session);
            }
        }
    }

    /** Yeni bir WalEvent'i bağlı tüm dashboard istemcilerine JSON olarak yollar. */
    public void broadcast(WalEvent event) {
        String json;
        try {
            json = MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            log.error("Failed to serialize event to JSON", e);
            return;
        }

        for (Session session : SESSIONS) {
            if (!session.isOpen()) continue;
            try {
                session.getRemote().sendString(json);
            } catch (IOException e) {
                log.debug("Failed to send to client, closing connection", e);
                SESSIONS.remove(session);
            }
        }
    }

    // ─── WebSocket uç noktası ───────────────────────────────────────────────

    public static class WalEventEndpoint extends WebSocketAdapter {
        @Override
        public void onWebSocketConnect(Session session) {
            super.onWebSocketConnect(session);
            SESSIONS.add(session);
            log.info("Dashboard client connected: {}", session.getRemoteAddress());
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            SESSIONS.remove(getSession());
            super.onWebSocketClose(statusCode, reason);
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            SESSIONS.remove(getSession());
            log.debug("Dashboard connection error", cause);
        }
    }

    // ─── Statik dashboard sayfası ───────────────────────────────────────────

    private static class IndexServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.setContentType("text/html; charset=UTF-8");
            try (InputStream in = getClass().getResourceAsStream("/static/index.html");
                 OutputStream out = resp.getOutputStream()) {
                if (in == null) {
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                in.transferTo(out);
            }
        }
    }
}
