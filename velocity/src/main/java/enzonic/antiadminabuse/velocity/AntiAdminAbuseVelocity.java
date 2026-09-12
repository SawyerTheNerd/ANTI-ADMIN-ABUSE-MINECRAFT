package enzonic.antiadminabuse.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import enzonic.antiadminabuse.core.AbuseConfig;
import enzonic.antiadminabuse.core.AbuseService;
import enzonic.antiadminabuse.core.CommandRecord;
import enzonic.antiadminabuse.core.Log;
import enzonic.antiadminabuse.core.SimpleYaml;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Velocity proxy entry point.
 *
 * Velocity exposes a proper {@link CommandExecuteEvent}, which is cleaner than
 * BungeeCord's chat-based hook: it fires for console and player sources alike.
 *
 * Velocity ships no YAML parser on the plugin classpath, so the config is read
 * with {@link SimpleYaml} from core rather than shading SnakeYAML in.
 */
@Plugin(
        id = "antiadminabuse",
        name = "AntiAdminAbuse",
        version = "2.0.0",
        description = "Logs every command run on the proxy to a Discord webhook.",
        authors = { "SawyerPlaz" },
        url = "https://modrinth.com/plugin/anti-admin-abuse"
)
public final class AntiAdminAbuseVelocity {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private AbuseService service;

    @Inject
    public AntiAdminAbuseVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        service = new AbuseService(new Slf4jLog(logger), readConfig());
        service.start();
        if (!service.config().hasUsableWebhook()) {
            logger.warn("No webhook URL configured. Edit {}/config.yml.", dataDirectory);
        }
        logger.info("AntiAdminAbuse (Velocity) enabled.");
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (service != null) {
            service.stop();
            service = null;
        }
    }

    // LAST so the record reflects the final decision of every other plugin.
    @Subscribe(order = PostOrder.LAST)
    public void onCommand(CommandExecuteEvent event) {
        if (!event.getResult().isAllowed()) return;

        CommandSource source = event.getCommandSource();
        String name;
        String uuid = null;
        String backend = null;
        boolean privileged;
        CommandRecord.Source kind;

        if (source instanceof Player) {
            Player player = (Player) source;
            name = player.getUsername();
            uuid = player.getUniqueId().toString();
            backend = player.getCurrentServer().isPresent()
                    ? player.getCurrentServer().get().getServerInfo().getName() : null;
            privileged = player.hasPermission("antiadminabuse.staff");
            kind = CommandRecord.Source.PROXY;
        } else {
            name = "Console";
            privileged = true;
            kind = CommandRecord.Source.CONSOLE;
        }

        service.record(new CommandRecord(
                name, uuid, kind,
                // Velocity strips the slash from the command line; restore it so the
                // Discord output matches the other platforms.
                "/" + event.getCommand(),
                backend, privileged, System.currentTimeMillis()));
    }

    /** Writes the bundled default config on first run, then parses it. */
    private AbuseConfig readConfig() {
        AbuseConfig cfg = new AbuseConfig();
        cfg.serverName = "proxy";
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.yml");
            if (!Files.exists(file)) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) Files.copy(in, file);
                }
            }
            if (!Files.exists(file)) return cfg;
            // Velocity ships no YAML parser, so core's small reader does the work.
            return SimpleYaml.parse(Files.readAllLines(file, StandardCharsets.UTF_8)).toConfig(cfg);
        } catch (IOException e) {
            logger.warn("Could not read config.yml; using defaults.", e);
            return cfg;
        }
    }

    private static final class Slf4jLog implements Log {
        private final Logger logger;

        Slf4jLog(Logger logger) { this.logger = logger; }

        public void info(String message) { logger.info(message); }
        public void warn(String message) { logger.warn(message); }
        public void warn(String message, Throwable error) { logger.warn(message, error); }
    }
}
