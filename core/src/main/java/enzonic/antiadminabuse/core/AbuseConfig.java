package enzonic.antiadminabuse.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolved settings, independent of how any given platform stores them.
 *
 * Each platform reads its native config format (Bukkit YAML, Velocity TOML, ...)
 * and fills one of these in, so filtering behaviour is identical everywhere.
 */
public final class AbuseConfig {

    /**
     * Commands whose arguments are replaced with {@code ***} before leaving the server.
     *
     * Includes this plugin's own command aliases: {@code /aaa setwebhook <url>} has
     * the command name {@code aaa}, so without them the webhook URL -- a bearer
     * credential -- would be logged to the very channel it points at.
     */
    public static final List<String> DEFAULT_REDACTED = Collections.unmodifiableList(Arrays.asList(
            "login", "l", "register", "reg", "changepassword", "changepass", "cp",
            "auth", "authme", "password", "passwd", "premium", "unregister",
            "2fa", "totp", "op", "token", "webhook", "setwebhook",
            "aaa", "antiadminabuse", "antiabuse"));

    /** Noise that would otherwise flood the channel and hide real activity. */
    public static final List<String> DEFAULT_IGNORED = Collections.unmodifiableList(Arrays.asList(
            "msg", "tell", "w", "r", "reply", "me", "helpop", "list", "tps", "ping", "spawn", "help"));

    public boolean enabled = true;
    public String webhookUrl = "";
    /** Name shown as the Discord message author. Empty = leave the webhook default. */
    public String username = "Anti Admin Abuse";
    /** Free-form label so one channel can serve several servers. */
    public String serverName = "";

    public boolean logPlayerCommands = true;
    public boolean logConsoleCommands = true;
    /** When true, only commands from operators / permission holders are reported. */
    public boolean onlyPrivileged = false;

    /** If non-empty, ONLY these commands are reported (allow-list mode). */
    public Set<String> watchedCommands = new LinkedHashSet<String>();
    public Set<String> ignoredCommands = new LinkedHashSet<String>(DEFAULT_IGNORED);
    public Set<String> redactedCommands = new LinkedHashSet<String>(DEFAULT_REDACTED);

    /** Render as a Discord embed rather than a plain content line. */
    public boolean useEmbed = true;

    public int connectTimeoutMillis = 5000;
    public int readTimeoutMillis = 8000;
    /** Retries after a 429/5xx before the entry is dropped. */
    public int maxRetries = 3;
    /** Upper bound on queued entries; protects memory if Discord is unreachable. */
    public int queueCapacity = 2000;

    /** Normalises a raw command string to a bare lowercase name, e.g. {@code "/Give a b"} -> {@code "give"}. */
    public static String commandName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("/")) s = s.substring(1);
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') { cut = i; break; }
        }
        s = s.substring(0, cut);
        // Strip a plugin qualifier such as "minecraft:give" or "essentials:gamemode".
        int colon = s.indexOf(':');
        if (colon >= 0 && colon + 1 < s.length()) s = s.substring(colon + 1);
        return s.toLowerCase(Locale.ROOT);
    }

    /** Lowercases and de-slashes a configured list so comparisons are predictable. */
    public static Set<String> normalizeList(Iterable<String> values) {
        Set<String> out = new LinkedHashSet<String>();
        if (values == null) return out;
        for (String v : values) {
            if (v == null) continue;
            String n = commandName(v);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    /** True when the webhook URL is actually usable (not blank, not the placeholder). */
    public boolean hasUsableWebhook() {
        if (webhookUrl == null) return false;
        String u = webhookUrl.trim();
        if (u.isEmpty()) return false;
        if (u.toUpperCase(Locale.ROOT).contains("YOUR_DISCORD_WEBHOOK_URL_HERE")) return false;
        return u.startsWith("http://") || u.startsWith("https://");
    }

    public List<String> validate() {
        List<String> problems = new ArrayList<String>();
        if (!hasUsableWebhook()) {
            problems.add("webhook-url is not set - command logging is inactive until you run /aaa setwebhook <url>");
        }
        if (queueCapacity < 1) problems.add("queue-capacity must be >= 1");
        if (maxRetries < 0) problems.add("max-retries must be >= 0");
        return problems;
    }
}
