package enzonic.antiadminabuse.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The whole behaviour of the plugin, expressed without touching any server API.
 *
 * Platform modules do exactly three things: build a {@link CommandRecord},
 * hand it to {@link #record}, and forward config changes. Keeping the decision
 * logic here is what makes the Bukkit, BungeeCord, Velocity, Fabric and
 * NeoForge builds behave identically instead of drifting apart.
 */
public final class AbuseService {

    private final Log log;
    private volatile AbuseConfig config;
    private final WebhookSender sender;

    public AbuseService(Log log, AbuseConfig config) {
        this.log = log == null ? Log.NOOP : log;
        this.config = config;
        this.sender = new WebhookSender(this.log, config);
    }

    public void start() {
        sender.start();
        for (String problem : config.validate()) {
            log.warn(problem);
        }
    }

    public void stop() {
        sender.shutdown(2000);
    }

    public AbuseConfig config() {
        return config;
    }

    public WebhookSender sender() {
        return sender;
    }

    /** Swaps in new settings at runtime (config reload, or /aaa setwebhook). */
    public void updateConfig(AbuseConfig updated) {
        this.config = updated;
        sender.applyConfig(updated);
    }

    /**
     * Decides whether {@code record} is worth reporting and, if so, queues it.
     *
     * @return true when the record was queued for delivery
     */
    public boolean record(CommandRecord record) {
        AbuseConfig cfg = config;
        if (!cfg.enabled || !cfg.hasUsableWebhook()) return false;
        if (!shouldReport(record, cfg)) return false;

        sender.enqueue(buildPayload(record, cfg));
        return true;
    }

    /** Pure filtering decision, split out so it is directly unit-testable. */
    public static boolean shouldReport(CommandRecord record, AbuseConfig cfg) {
        if (record.command == null || record.command.trim().isEmpty()) return false;

        boolean fromConsole = record.source == CommandRecord.Source.CONSOLE
                || record.source == CommandRecord.Source.RCON;
        if (fromConsole && !cfg.logConsoleCommands) return false;
        if (!fromConsole && !cfg.logPlayerCommands) return false;

        // "Only privileged" is about catching staff abuse, so it never filters
        // out console/RCON, which are inherently privileged.
        if (cfg.onlyPrivileged && !fromConsole && !record.privileged) return false;

        String name = record.commandName();
        if (name.isEmpty()) return false;

        // Allow-list mode wins over the ignore list when configured.
        if (cfg.watchedCommands != null && !cfg.watchedCommands.isEmpty()) {
            return cfg.watchedCommands.contains(name);
        }
        if (cfg.ignoredCommands != null && cfg.ignoredCommands.contains(name)) return false;
        return true;
    }

    /**
     * Strips arguments from commands that carry secrets.
     *
     * A command logger that faithfully forwards {@code /login hunter2} to Discord
     * turns an accountability tool into a credential leak, so redaction is on by
     * default rather than opt-in.
     */
    public static String redact(String command, AbuseConfig cfg) {
        if (command == null) return "";
        String name = AbuseConfig.commandName(command);
        if (cfg.redactedCommands == null || !cfg.redactedCommands.contains(name)) {
            // Even for a command we were not told to redact, never let a webhook
            // URL through: it is a bearer credential, and any plugin may take one
            // as an argument under a name we cannot know in advance.
            return scrubWebhookUrls(command);
        }
        String trimmed = command.trim();
        boolean slash = trimmed.startsWith("/");
        if (slash) trimmed = trimmed.substring(1);
        int space = trimmed.indexOf(' ');
        if (space < 0) return (slash ? "/" : "") + trimmed;
        String head = trimmed.substring(0, space);
        return (slash ? "/" : "") + head + " ***";
    }

    /**
     * Replaces any Discord webhook URL found anywhere in the text.
     *
     * Deliberately independent of the command name: {@code /aaa setwebhook <url>},
     * a third-party {@code /discordsrv} command, or a typo that puts the URL in an
     * unexpected place would all otherwise publish the credential into the very
     * channel it grants access to.
     */
    static String scrubWebhookUrls(String text) {
        if (text == null || text.isEmpty()) return text;
        // Cheap pre-check so the common case does no regex work at all.
        int marker = text.indexOf("/api/webhooks");
        if (marker < 0) return text;
        return WEBHOOK_URL.matcher(text).replaceAll("<webhook-url-redacted>");
    }

    private static final java.util.regex.Pattern WEBHOOK_URL = java.util.regex.Pattern.compile(
            "https?://\\S*?/api/webhooks/\\S*", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Truncates to fit Discord's limits, leaving room for surrounding markup. */
    static String clamp(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        if (max <= 3) return value.substring(0, max);
        return value.substring(0, max - 3) + "...";
    }

    private static String isoTimestamp(long millis) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(millis));
    }

    /** Builds the Discord webhook JSON body for a record. */
    public static String buildPayload(CommandRecord record, AbuseConfig cfg) {
        String command = clamp(redact(record.command, cfg), 900);
        String sender = clamp(record.senderName, 200);

        StringBuilder json = new StringBuilder(512);
        json.append('{');
        if (cfg.username != null && !cfg.username.trim().isEmpty()) {
            json.append("\"username\":").append(Json.quote(clamp(cfg.username.trim(), 80))).append(',');
        }
        // Never let a logged command ping roles or @everyone.
        json.append("\"allowed_mentions\":{\"parse\":[]},");

        if (cfg.useEmbed) {
            json.append("\"embeds\":[{");
            json.append("\"title\":").append(Json.quote(sourceLabel(record.source))).append(',');
            json.append("\"color\":").append(colorFor(record)).append(',');
            json.append("\"timestamp\":").append(Json.quote(isoTimestamp(record.timestampMillis))).append(',');
            json.append("\"description\":").append(Json.quote("```\n" + command + "\n```")).append(',');
            json.append("\"fields\":[");
            json.append("{\"name\":\"Sender\",\"value\":").append(Json.quote(sender))
                .append(",\"inline\":true}");
            if (record.senderId != null && !record.senderId.isEmpty()) {
                json.append(",{\"name\":\"UUID\",\"value\":").append(Json.quote(clamp(record.senderId, 80)))
                    .append(",\"inline\":true}");
            }
            if (record.location != null && !record.location.isEmpty()) {
                json.append(",{\"name\":\"Location\",\"value\":").append(Json.quote(clamp(record.location, 200)))
                    .append(",\"inline\":true}");
            }
            if (cfg.serverName != null && !cfg.serverName.trim().isEmpty()) {
                json.append(",{\"name\":\"Server\",\"value\":").append(Json.quote(clamp(cfg.serverName.trim(), 200)))
                    .append(",\"inline\":true}");
            }
            json.append("]}]");
        } else {
            StringBuilder line = new StringBuilder();
            if (cfg.serverName != null && !cfg.serverName.trim().isEmpty()) {
                line.append('[').append(cfg.serverName.trim()).append("] ");
            }
            line.append("**").append(sender).append("** (").append(sourceLabel(record.source))
                .append(") executed: `").append(command).append('`');
            json.append("\"content\":")
                .append(Json.quote(clamp(line.toString(), WebhookSender.DISCORD_CONTENT_LIMIT)));
        }
        json.append('}');
        return json.toString();
    }

    private static String sourceLabel(CommandRecord.Source source) {
        switch (source) {
            case CONSOLE: return "Console command";
            case RCON:    return "RCON command";
            case PROXY:   return "Proxy command";
            default:      return "Player command";
        }
    }

    /** Console/RCON stands out in red; privileged players amber; others grey. */
    private static int colorFor(CommandRecord record) {
        if (record.source == CommandRecord.Source.CONSOLE || record.source == CommandRecord.Source.RCON) {
            return 0xE74C3C;
        }
        return record.privileged ? 0xF1C40F : 0x95A5A6;
    }
}
