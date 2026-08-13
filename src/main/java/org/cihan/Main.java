package org.cihan;

import org.cihan.WalConsumer.WalConsumer;
import org.cihan.WalConsumer.WalConsumerConfig;
import org.cihan.websocket.WalWebSocketServer;

import java.util.concurrent.CountDownLatch;

public class Main {

    private static final int DASHBOARD_PORT = 8080;

    public static void main(String[] args) throws Exception {
        WalWebSocketServer dashboard = new WalWebSocketServer(DASHBOARD_PORT);
        dashboard.start();

        WalConsumerConfig config = new WalConsumerConfig();
        WalConsumer consumer = new WalConsumer(config, event -> {
            System.out.println(event);
            dashboard.broadcast(event);
        });
        consumer.start();

        System.out.println("Dashboard: http://localhost:" + DASHBOARD_PORT + "/");

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            consumer.stop();
            try {
                dashboard.stop();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            shutdownLatch.countDown();
        }, "shutdown-hook"));

        shutdownLatch.await();
    }
}
