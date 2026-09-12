package enzonic.antiadminabuse.bukkit;

import enzonic.antiadminabuse.core.AbuseConfig;
import enzonic.antiadminabuse.core.AbuseService;
import enzonic.antiadminabuse.core.Log;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;


import java.util.logging.Level;

/**
 * Bukkit-family entry point: Bukkit, Spigot, Paper, Purpur, Pufferfish and Folia.
 *
 * Deliberately thin. All decisions live in {@code core}; this class only adapts
 * Bukkit's config and event systems onto it. Note the total absence of scheduler
 * use -- the webhook thread is owned by core, which is what lets the identical
 * jar run on Folia, where there is no single main thread to schedule onto.
 */
public class AntiAdminAbusePlugin extends JavaPlugin {

    private AbuseService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        service = new AbuseService(new BukkitLog(this), readConfig());
        service.start();

        getServer().getPluginManager().registerEvents(new CommandWatcher(this, service), this);
        registerCommand("antiadminabuse", new AbuseCommand(this, service));
        // Kept so existing setups that documented /setwebhook keep working.
        registerCommand("setwebhook", new AbuseCommand(this, service));

        AbuseConfig cfg = service.config();
        if (!cfg.hasUsableWebhook()) {
            getLogger().warning("No webhook URL configured yet. Run /aaa setwebhook <url> or edit config.yml.");
        }
        getLogger().info("AntiAdminAbuse v" + getDescription().getVersion() + " enabled"
                + " (player logging: " + cfg.logPlayerCommands
                + ", console logging: " + cfg.logConsoleCommands + ")");
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.stop();
            service = null;
        }
    }

    private void registerCommand(String name, AbuseCommand executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            // Only happens if plugin.yml and this class disagree; warn instead of NPE.
            getLogger().warning("Command '" + name + "' is missing from plugin.yml; skipping registration.");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    /** Re-reads config.yml from disk and pushes the result into the service. */
    public void reload() {
        reloadConfig();
        service.updateConfig(readConfig());
    }

    /** Persists a new webhook URL and applies it immediately. */
    public void saveWebhookUrl(String url) {
        getConfig().set("webhook-url", url);
        saveConfig();
        AbuseConfig updated = readConfig();
        updated.webhookUrl = url;
        service.updateConfig(updated);
    }

    /** Translates Bukkit's YAML into the platform-neutral config object. */
    private AbuseConfig readConfig() {
        FileConfiguration yaml = getConfig();
        AbuseConfig cfg = new AbuseConfig();

        cfg.enabled = yaml.getBoolean("enabled", cfg.enabled);
        cfg.webhookUrl = yaml.getString("webhook-url", "");
        cfg.username = yaml.getString("discord-username", cfg.username);
        cfg.serverName = yaml.getString("server-name", "");
        cfg.useEmbed = yaml.getBoolean("use-embed", cfg.useEmbed);

        cfg.logPlayerCommands = yaml.getBoolean("log-player-commands", cfg.logPlayerCommands);
        cfg.logConsoleCommands = yaml.getBoolean("log-console-commands", cfg.logConsoleCommands);
        cfg.onlyPrivileged = yaml.getBoolean("only-staff-commands", cfg.onlyPrivileged);

        cfg.watchedCommands = AbuseConfig.normalizeList(yaml.getStringList("watched-commands"));
        if (yaml.isSet("ignored-commands")) {
            cfg.ignoredCommands = AbuseConfig.normalizeList(yaml.getStringList("ignored-commands"));
        }
        if (yaml.isSet("redacted-commands")) {
            cfg.redactedCommands = AbuseConfig.normalizeList(yaml.getStringList("redacted-commands"));
        }

        cfg.connectTimeoutMillis = yaml.getInt("connect-timeout-millis", cfg.connectTimeoutMillis);
        cfg.readTimeoutMillis = yaml.getInt("read-timeout-millis", cfg.readTimeoutMillis);
        cfg.maxRetries = yaml.getInt("max-retries", cfg.maxRetries);
        cfg.queueCapacity = yaml.getInt("queue-capacity", cfg.queueCapacity);
        return cfg;
    }

    /** Adapts {@link java.util.logging.Logger} onto core's logging seam. */
    private static final class BukkitLog implements Log {
        private final JavaPlugin plugin;

        BukkitLog(JavaPlugin plugin) { this.plugin = plugin; }

        public void info(String message) { plugin.getLogger().info(message); }
        public void warn(String message) { plugin.getLogger().warning(message); }
        public void warn(String message, Throwable error) {
            plugin.getLogger().log(Level.WARNING, message, error);
        }
    }
}
