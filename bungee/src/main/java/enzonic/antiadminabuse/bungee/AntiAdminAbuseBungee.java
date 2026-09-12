package enzonic.antiadminabuse.bungee;

import enzonic.antiadminabuse.core.AbuseConfig;
import enzonic.antiadminabuse.core.AbuseService;
import enzonic.antiadminabuse.core.CommandRecord;
import enzonic.antiadminabuse.core.Log;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import net.md_5.bungee.api.event.ChatEvent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.logging.Level;

/**
 * BungeeCord / Waterfall entry point.
 *
 * A proxy sees commands before they reach a backend server, so this catches
 * proxy-level commands (/server, /send, /alert and anything a proxy plugin adds)
 * that a Bukkit-side plugin never observes. Run this alongside the Bukkit plugin
 * to cover both layers; on its own it only sees the proxy layer.
 *
 * BungeeCord has no dedicated player-command event, so {@link ChatEvent} is the
 * documented hook -- it fires for chat and commands alike, and we filter to the
 * latter.
 */
public final class AntiAdminAbuseBungee extends Plugin implements Listener {

    private AbuseService service;

    @Override
    public void onEnable() {
        Configuration yaml = loadConfig();
        service = new AbuseService(new BungeeLog(this), readConfig(yaml));
        service.start();
        getProxy().getPluginManager().registerListener(this, this);

        if (!service.config().hasUsableWebhook()) {
            getLogger().warning("No webhook URL configured. Edit plugins/AntiAdminAbuse/config.yml.");
        }
        getLogger().info("AntiAdminAbuse (BungeeCord) enabled.");
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.stop();
            service = null;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(ChatEvent event) {
        // Chat that is not a command is none of our business, and a command another
        // plugin already cancelled never ran.
        if (event.isCancelled() || !event.isCommand()) return;
        if (!(event.getSender() instanceof ProxiedPlayer)) return;

        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        String backend = null;
        if (player.getServer() != null && player.getServer().getInfo() != null) {
            backend = player.getServer().getInfo().getName();
        }
        service.record(new CommandRecord(
                player.getName(),
                player.getUniqueId() == null ? null : player.getUniqueId().toString(),
                CommandRecord.Source.PROXY,
                event.getMessage(),
                backend,
                isPrivileged(player),
                System.currentTimeMillis()));
    }

    private static boolean isPrivileged(CommandSender sender) {
        return sender.hasPermission("antiadminabuse.staff")
                || sender.hasPermission("bungeecord.command.end")
                || sender.getGroups().contains("admin");
    }

    /** Copies the bundled default config on first run, then reads it. */
    private Configuration loadConfig() {
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                getLogger().warning("Could not create " + getDataFolder());
            }
            File file = new File(getDataFolder(), "config.yml");
            if (!file.exists()) {
                InputStream in = getResourceAsStream("config.yml");
                if (in != null) {
                    OutputStream out = new java.io.FileOutputStream(file);
                    try {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    } finally {
                        out.close();
                        in.close();
                    }
                }
            }
            return ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not read config.yml; using defaults.", e);
            return new Configuration();
        }
    }

    private AbuseConfig readConfig(Configuration yaml) {
        AbuseConfig cfg = new AbuseConfig();
        cfg.enabled = yaml.getBoolean("enabled", cfg.enabled);
        cfg.webhookUrl = yaml.getString("webhook-url", "");
        cfg.username = yaml.getString("discord-username", cfg.username);
        cfg.serverName = yaml.getString("server-name", "proxy");
        cfg.useEmbed = yaml.getBoolean("use-embed", cfg.useEmbed);
        cfg.logPlayerCommands = yaml.getBoolean("log-player-commands", cfg.logPlayerCommands);
        // A proxy has no console command event, so this setting has nothing to act on.
        cfg.logConsoleCommands = false;
        cfg.onlyPrivileged = yaml.getBoolean("only-staff-commands", cfg.onlyPrivileged);

        cfg.watchedCommands = AbuseConfig.normalizeList(stringList(yaml, "watched-commands"));
        List<String> ignored = stringList(yaml, "ignored-commands");
        if (!ignored.isEmpty()) cfg.ignoredCommands = AbuseConfig.normalizeList(ignored);
        List<String> redacted = stringList(yaml, "redacted-commands");
        if (!redacted.isEmpty()) cfg.redactedCommands = AbuseConfig.normalizeList(redacted);

        cfg.connectTimeoutMillis = yaml.getInt("connect-timeout-millis", cfg.connectTimeoutMillis);
        cfg.readTimeoutMillis = yaml.getInt("read-timeout-millis", cfg.readTimeoutMillis);
        cfg.maxRetries = yaml.getInt("max-retries", cfg.maxRetries);
        cfg.queueCapacity = yaml.getInt("queue-capacity", cfg.queueCapacity);
        return cfg;
    }

    private static List<String> stringList(Configuration yaml, String path) {
        return yaml.getStringList(path);
    }

    private static final class BungeeLog implements Log {
        private final Plugin plugin;

        BungeeLog(Plugin plugin) { this.plugin = plugin; }

        public void info(String message) { plugin.getLogger().info(message); }
        public void warn(String message) { plugin.getLogger().warning(message); }
        public void warn(String message, Throwable error) {
            plugin.getLogger().log(Level.WARNING, message, error);
        }
    }
}
