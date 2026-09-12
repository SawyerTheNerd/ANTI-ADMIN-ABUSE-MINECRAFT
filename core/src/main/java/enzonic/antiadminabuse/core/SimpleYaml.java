package enzonic.antiadminabuse.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reader for the small YAML subset this project's config files use.
 *
 * Bukkit and BungeeCord both ship a real YAML parser, but Velocity, Fabric and
 * NeoForge do not, and shading SnakeYAML into three separate artifacts to read
 * about twenty scalar settings is a poor trade. This handles exactly the shapes
 * our own config uses -- {@code key: value}, {@code key: [a, b]} and block lists
 * of {@code - item} -- and ignores anything else rather than guessing.
 *
 * It is not a general YAML parser and is not meant to become one.
 */
public final class SimpleYaml {

    private final Map<String, String> scalars = new LinkedHashMap<String, String>();
    private final Map<String, List<String>> lists = new LinkedHashMap<String, List<String>>();

    public static SimpleYaml parse(List<String> lines) {
        SimpleYaml yaml = new SimpleYaml();
        yaml.read(lines);
        return yaml;
    }

    public static SimpleYaml read(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        List<String> lines = new ArrayList<String>();
        String line;
        while ((line = reader.readLine()) != null) lines.add(line);
        return parse(lines);
    }

    private void read(List<String> lines) {
        String pendingListKey = null;
        for (String raw : lines) {
            String line = raw;
            int comment = indexOfComment(line);
            if (comment >= 0) line = line.substring(0, comment);
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.equals("-") || trimmed.startsWith("- ")) {
                if (pendingListKey != null) {
                    String item = unquote(trimmed.length() > 1 ? trimmed.substring(1).trim() : "");
                    if (!item.isEmpty()) listFor(pendingListKey).add(item);
                }
                continue;
            }

            int colon = trimmed.indexOf(':');
            if (colon < 0) continue;
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();

            if (value.isEmpty()) {
                // A bare "key:" opens a block list. Register it now so an empty
                // block is still reported as present-but-empty.
                pendingListKey = key;
                listFor(key);
                continue;
            }
            // Any scalar or inline list closes the previous block.
            pendingListKey = null;
            if (value.startsWith("[") && value.endsWith("]")) {
                List<String> items = listFor(key);
                items.clear();
                String inner = value.substring(1, value.length() - 1).trim();
                if (!inner.isEmpty()) {
                    for (String part : inner.split(",")) {
                        String item = unquote(part.trim());
                        if (!item.isEmpty()) items.add(item);
                    }
                }
            } else {
                scalars.put(key, unquote(value));
            }
        }
    }

    private List<String> listFor(String key) {
        List<String> existing = lists.get(key);
        if (existing == null) {
            existing = new ArrayList<String>();
            lists.put(key, existing);
        }
        return existing;
    }

    /** Finds a trailing comment, ignoring a '#' that sits inside quotes. */
    private static int indexOfComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '#' && !inSingle && !inDouble) return i;
        }
        return -1;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    // ------------------------------------------------------------------ access

    public boolean hasList(String key) {
        return lists.containsKey(key);
    }

    public List<String> list(String key) {
        List<String> value = lists.get(key);
        return value == null ? new ArrayList<String>() : value;
    }

    public String string(String key, String fallback) {
        String value = scalars.get(key);
        return value == null ? fallback : value;
    }

    public boolean bool(String key, boolean fallback) {
        String value = scalars.get(key);
        if (value == null) return fallback;
        return Arrays.asList("true", "yes", "on", "1").contains(value.toLowerCase(Locale.ROOT));
    }

    public int integer(String key, int fallback) {
        String value = scalars.get(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Fills an {@link AbuseConfig} from the parsed document. */
    public AbuseConfig toConfig(AbuseConfig cfg) {
        cfg.enabled = bool("enabled", cfg.enabled);
        cfg.webhookUrl = string("webhook-url", cfg.webhookUrl);
        cfg.username = string("discord-username", cfg.username);
        cfg.serverName = string("server-name", cfg.serverName);
        cfg.useEmbed = bool("use-embed", cfg.useEmbed);
        cfg.logPlayerCommands = bool("log-player-commands", cfg.logPlayerCommands);
        cfg.logConsoleCommands = bool("log-console-commands", cfg.logConsoleCommands);
        cfg.onlyPrivileged = bool("only-staff-commands", cfg.onlyPrivileged);
        cfg.connectTimeoutMillis = integer("connect-timeout-millis", cfg.connectTimeoutMillis);
        cfg.readTimeoutMillis = integer("read-timeout-millis", cfg.readTimeoutMillis);
        cfg.maxRetries = integer("max-retries", cfg.maxRetries);
        cfg.queueCapacity = integer("queue-capacity", cfg.queueCapacity);

        if (hasList("watched-commands")) {
            cfg.watchedCommands = AbuseConfig.normalizeList(list("watched-commands"));
        }
        if (hasList("ignored-commands") && !list("ignored-commands").isEmpty()) {
            cfg.ignoredCommands = AbuseConfig.normalizeList(list("ignored-commands"));
        }
        if (hasList("redacted-commands") && !list("redacted-commands").isEmpty()) {
            cfg.redactedCommands = AbuseConfig.normalizeList(list("redacted-commands"));
        }
        return cfg;
    }
}
