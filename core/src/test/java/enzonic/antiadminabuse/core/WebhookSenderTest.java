package enzonic.antiadminabuse.core;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Exercises real HTTP delivery against a throwaway listener on localhost.
 *
 * This is deliberately not mocked: the bugs worth catching here (blocking the
 * caller, malformed framing, missing retry on 429, losing queued entries at
 * shutdown) only show up when actual bytes cross a socket.
 */
public class WebhookSenderTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Minimal HTTP/1.1 server that records request bodies and replies on cue. */
    private static final class CaptureServer implements AutoCloseable {
        final ServerSocket socket;
        final List<String> bodies = new CopyOnWriteArrayList<String>();
        final List<Integer> statuses = new CopyOnWriteArrayList<Integer>();
        final Thread acceptor;
        volatile boolean stopped;
        CountDownLatch latch;

        CaptureServer(final List<Integer> replyScript) throws IOException {
            socket = new ServerSocket(0);
            statuses.addAll(replyScript);
            acceptor = new Thread(new Runnable() {
                public void run() { loop(); }
            }, "capture-server");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() { return socket.getLocalPort(); }

        private void loop() {
            int served = 0;
            while (!stopped) {
                Socket client = null;
                try {
                    client = socket.accept();
                    handle(client, served++);
                } catch (IOException e) {
                    return; // socket closed, we are done
                } finally {
                    if (client != null) try { client.close(); } catch (IOException ignored) { }
                }
            }
        }

        private void handle(Socket client, int index) throws IOException {
            InputStream in = client.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF8));
            int contentLength = 0;
            String line;
            List<String> headers = new ArrayList<String>();
            // Request line + headers, terminated by a blank line.
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                headers.add(line);
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            char[] body = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = reader.read(body, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            bodies.add(new String(body, 0, Math.max(0, read)));

            int status = index < statuses.size() ? statuses.get(index) : 204;
            OutputStream out = client.getOutputStream();
            StringBuilder response = new StringBuilder();
            response.append("HTTP/1.1 ").append(status).append(" X\r\n");
            if (status == 429) {
                response.append("Retry-After: 0.05\r\n");
            }
            response.append("Content-Length: 0\r\nConnection: close\r\n\r\n");
            out.write(response.toString().getBytes(UTF8));
            out.flush();
            if (latch != null) latch.countDown();
        }

        public void close() {
            stopped = true;
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    /** Polls until the sender has counted {@code target} deliveries, or times out. */
    private static boolean awaitDelivered(AbuseService service, long target, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (service.sender().deliveredCount() >= target) return true;
            Thread.sleep(25);
        }
        return service.sender().deliveredCount() >= target;
    }

    private static AbuseConfig configFor(CaptureServer server) {
        AbuseConfig cfg = new AbuseConfig();
        cfg.webhookUrl = "http://127.0.0.1:" + server.port() + "/webhook";
        cfg.connectTimeoutMillis = 2000;
        cfg.readTimeoutMillis = 2000;
        return cfg;
    }

    @Test(timeout = 20000)
    public void deliversWellFormedJsonBody() throws Exception {
        CaptureServer server = new CaptureServer(java.util.Arrays.asList(204));
        try {
            server.latch = new CountDownLatch(1);
            AbuseConfig cfg = configFor(server);
            AbuseService service = new AbuseService(Log.NOOP, cfg);
            service.start();

            service.record(new CommandRecord("Steve", "abc-123", CommandRecord.Source.PLAYER,
                    "/ban Bob \"rude\"", "world", true, System.currentTimeMillis()));

            assertTrue("server never received the request", server.latch.await(10, TimeUnit.SECONDS));
            assertEquals(1, server.bodies.size());
            String body = server.bodies.get(0);
            assertTrue(body.contains("Steve"));
            assertTrue(body.contains("/ban Bob"));
            // The embedded quotes must arrive escaped, not raw.
            assertTrue(body.contains("\\\"rude\\\""));
            assertTrue(body.startsWith("{") && body.endsWith("}"));
            // The latch fires when the server finishes replying, which is slightly
            // before the sender has read that reply, so poll rather than race it.
            assertTrue("delivery was never counted", awaitDelivered(service, 1, 5000));
            service.stop();
        } finally {
            server.close();
        }
    }

    @Test(timeout = 20000)
    public void recordDoesNotBlockTheCallingThread() throws Exception {
        // A webhook endpoint that never answers must not stall gameplay. Point at
        // a black-hole address and confirm record() returns effectively instantly.
        AbuseConfig cfg = new AbuseConfig();
        cfg.webhookUrl = "http://192.0.2.1:81/blackhole"; // TEST-NET-1, unroutable
        cfg.connectTimeoutMillis = 3000;
        AbuseService service = new AbuseService(Log.NOOP, cfg);
        service.start();

        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            service.record(new CommandRecord("Steve", null, CommandRecord.Source.PLAYER,
                    "/ban Bob" + i, "world", true, System.currentTimeMillis()));
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("record() took " + elapsedMillis + "ms; it must not do I/O inline", elapsedMillis < 500);
        service.stop();
    }

    @Test(timeout = 30000)
    public void retriesAfterRateLimit() throws Exception {
        CaptureServer server = new CaptureServer(java.util.Arrays.asList(429, 204));
        try {
            server.latch = new CountDownLatch(2);
            AbuseService service = new AbuseService(Log.NOOP, configFor(server));
            service.start();
            service.record(new CommandRecord("Steve", null, CommandRecord.Source.PLAYER,
                    "/op Bob", "world", true, System.currentTimeMillis()));

            assertTrue("expected a retry after 429", server.latch.await(15, TimeUnit.SECONDS));
            assertEquals(2, server.bodies.size());
            assertEquals(server.bodies.get(0), server.bodies.get(1));
            service.stop();
        } finally {
            server.close();
        }
    }

    @Test(timeout = 20000)
    public void permanentRejectionIsNotRetried() throws Exception {
        CaptureServer server = new CaptureServer(java.util.Arrays.asList(404, 204));
        try {
            server.latch = new CountDownLatch(1);
            AbuseService service = new AbuseService(Log.NOOP, configFor(server));
            service.start();
            service.record(new CommandRecord("Steve", null, CommandRecord.Source.PLAYER,
                    "/op Bob", "world", true, System.currentTimeMillis()));
            assertTrue(server.latch.await(10, TimeUnit.SECONDS));
            // Give a retry a chance to arrive, then prove it did not.
            Thread.sleep(1500);
            assertEquals("a 404 webhook must not be retried", 1, server.bodies.size());
            assertEquals(1, service.sender().failedCount());
            service.stop();
        } finally {
            server.close();
        }
    }

    @Test(timeout = 20000)
    public void fullQueueDropsInsteadOfBlocking() throws Exception {
        AbuseConfig cfg = new AbuseConfig();
        cfg.webhookUrl = "http://192.0.2.1:81/blackhole";
        cfg.queueCapacity = 4;
        cfg.connectTimeoutMillis = 3000;
        AbuseService service = new AbuseService(Log.NOOP, cfg);
        service.start();
        for (int i = 0; i < 200; i++) {
            service.record(new CommandRecord("Steve", null, CommandRecord.Source.PLAYER,
                    "/ban Bob" + i, "world", true, System.currentTimeMillis()));
        }
        assertTrue("expected overflow to be dropped", service.sender().droppedCount() > 0);
        assertTrue(service.sender().queueDepth() <= 4);
        service.stop();
    }

    @Test(timeout = 20000)
    public void queuedEntriesFlushOnShutdown() throws Exception {
        CaptureServer server = new CaptureServer(java.util.Arrays.asList(204, 204, 204));
        try {
            server.latch = new CountDownLatch(3);
            AbuseService service = new AbuseService(Log.NOOP, configFor(server));
            service.start();
            for (int i = 0; i < 3; i++) {
                service.record(new CommandRecord("Steve", null, CommandRecord.Source.PLAYER,
                        "/kick Bob" + i, "world", true, System.currentTimeMillis()));
            }
            service.stop();
            assertTrue("shutdown dropped queued entries", server.latch.await(10, TimeUnit.SECONDS));
            assertEquals(3, server.bodies.size());
        } finally {
            server.close();
        }
    }
}
