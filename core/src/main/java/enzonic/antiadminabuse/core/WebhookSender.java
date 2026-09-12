package enzonic.antiadminabuse.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Delivers payloads to a Discord webhook on a dedicated daemon thread.
 *
 * Why not just POST inline: the original code opened the connection on whatever
 * thread ran the command, i.e. the server main thread. Every command executed
 * stalled the whole server for a full HTTP round-trip to Discord, and a slow or
 * unreachable endpoint turned into a multi-second freeze per command. Doing the
 * I/O here also means the plugin never touches a platform scheduler, which is
 * what makes it work unchanged on Folia (no main thread to be wrong about) and
 * on the proxies.
 *
 * Java 8 only: {@link HttpURLConnection}, no java.net.http.
 */
public final class WebhookSender {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** Discord's hard cap on webhook message content. */
    public static final int DISCORD_CONTENT_LIMIT = 2000;

    private final Log log;
    private final BlockingQueue<String> queue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private volatile String endpoint;
    private volatile int connectTimeout;
    private volatile int readTimeout;
    private volatile int maxRetries;
    private Thread worker;

    /** Poison pill pushed onto the queue to wake the worker for shutdown. */
    private static final String STOP = new String("__AAA_STOP__");

    public WebhookSender(Log log, AbuseConfig config) {
        this.log = log == null ? Log.NOOP : log;
        this.queue = new ArrayBlockingQueue<String>(Math.max(1, config.queueCapacity));
        applyConfig(config);
    }

    public void applyConfig(AbuseConfig config) {
        this.endpoint = config.webhookUrl == null ? "" : config.webhookUrl.trim();
        this.connectTimeout = Math.max(250, config.connectTimeoutMillis);
        this.readTimeout = Math.max(250, config.readTimeoutMillis);
        this.maxRetries = Math.max(0, config.maxRetries);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = new Thread(new Runnable() {
            public void run() { pump(); }
        }, "AntiAdminAbuse-Webhook");
        // Daemon: a stuck HTTP call must never hold up server shutdown.
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Queues a payload for delivery. Never blocks the calling thread: if the
     * queue is full (Discord down, or a command flood) the entry is dropped and
     * counted rather than applying back-pressure to gameplay.
     */
    public void enqueue(String jsonPayload) {
        if (!running.get() || jsonPayload == null) return;
        if (!queue.offer(jsonPayload)) {
            long n = dropped.incrementAndGet();
            // Only complain occasionally; a flood must not become a log flood.
            if (n == 1 || n % 100 == 0) {
                log.warn("Webhook queue is full; dropped " + n + " command log(s). Is the webhook URL reachable?");
            }
        }
    }

    private void pump() {
        while (running.get()) {
            String payload;
            try {
                payload = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (payload == STOP) break;
            deliver(payload);
        }
        // Best-effort drain so a clean shutdown does not lose buffered entries.
        String remaining;
        while ((remaining = queue.poll()) != null) {
            if (remaining == STOP) continue;
            deliver(remaining);
        }
    }

    private void deliver(String payload) {
        String target = endpoint;
        if (target == null || target.isEmpty()) return;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            long retryAfterMillis = -1;
            try {
                HttpURLConnection conn = open(target);
                byte[] body = payload.getBytes(UTF8);
                conn.setFixedLengthStreamingMode(body.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(body);
                    os.flush();
                } finally {
                    os.close();
                }

                int status = conn.getResponseCode();
                if (status >= 200 && status < 300) {
                    delivered.incrementAndGet();
                    conn.disconnect();
                    return;
                }
                if (status == 429) {
                    retryAfterMillis = parseRetryAfter(conn);
                } else if (status < 500) {
                    // 4xx other than rate limiting will never succeed on retry.
                    failed.incrementAndGet();
                    log.warn("Discord rejected the command log (HTTP " + status + "): " + describe(conn)
                            + " - check that webhook-url is a valid Discord webhook.");
                    conn.disconnect();
                    return;
                }
                conn.disconnect();
            } catch (IOException e) {
                if (attempt >= maxRetries) {
                    failed.incrementAndGet();
                    log.warn("Failed to deliver command log to Discord: " + e);
                    return;
                }
            }

            if (attempt >= maxRetries) {
                failed.incrementAndGet();
                return;
            }
            // Exponential backoff, or Discord's own rate-limit hint when given.
            long sleep = retryAfterMillis >= 0 ? retryAfterMillis : (500L << attempt);
            try {
                Thread.sleep(Math.min(sleep, 30000L));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private HttpURLConnection open(String target) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "AntiAdminAbuse/2.0 (+https://github.com/SawyerTheNerd/ANTI-ADMIN-ABUSE-MINECRAFT)");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        return conn;
    }

    private static long parseRetryAfter(HttpURLConnection conn) {
        String header = conn.getHeaderField("Retry-After");
        if (header != null) {
            try {
                // Discord sends seconds here, sometimes fractional.
                return (long) (Double.parseDouble(header.trim()) * 1000.0);
            } catch (NumberFormatException ignored) {
                // fall through to the default backoff
            }
        }
        return -1;
    }

    /** Reads a short slice of the error body to make failures diagnosable. */
    private static String describe(HttpURLConnection conn) {
        InputStream err = conn.getErrorStream();
        if (err == null) return "<no response body>";
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[256];
            int read;
            int total = 0;
            while (total < 512 && (read = err.read(chunk)) > 0) {
                buf.write(chunk, 0, read);
                total += read;
            }
            return new String(buf.toByteArray(), UTF8).replace('\n', ' ').trim();
        } catch (IOException e) {
            return "<unreadable response body>";
        } finally {
            try { err.close(); } catch (IOException ignored) { }
        }
    }

    /** Stops the worker, giving queued entries a brief chance to flush. */
    public void shutdown(long graceMillis) {
        if (!running.compareAndSet(true, false)) return;
        queue.offer(STOP);
        Thread t = worker;
        if (t != null) {
            try {
                t.join(Math.max(0, graceMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) t.interrupt();
        }
    }

    public long deliveredCount() { return delivered.get(); }
    public long droppedCount()   { return dropped.get(); }
    public long failedCount()    { return failed.get(); }
    public int queueDepth()      { return queue.size(); }

    /** Blocks until the queue drains or the timeout expires. Test/diagnostic aid. */
    public boolean awaitDrain(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty()) return true;
            try {
                TimeUnit.MILLISECONDS.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return queue.isEmpty();
    }
}
