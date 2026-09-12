package enzonic.antiadminabuse.fabric;

import enzonic.antiadminabuse.core.AbuseConfig;
import enzonic.antiadminabuse.core.AbuseService;
import enzonic.antiadminabuse.core.Log;
import enzonic.antiadminabuse.core.SimpleYaml;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fabric entry point.
 *
 * Fabric has no command-execution event, so the actual interception is a mixin
 * ({@code CommandManagerMixin}); this class only owns configuration and the
 * service lifecycle. Unlike the Bukkit plugin, a Fabric build is tied to the
 * Minecraft version it was compiled against, because the mixin targets remapped
 * game classes.
 */
public final class AntiAdminAbuseFabric implements ModInitializer {

    public static final String MOD_ID = "antiadminabuse";
    private static final Logger LOGGER = LoggerFactory.getLogger("AntiAdminAbuse");

    @Override
    public void onInitialize() {
        AbuseService service = new AbuseService(new Slf4jLog(LOGGER), readConfig());
        service.start();
        CommandBridge.install(service);

        if (!service.config().hasUsableWebhook()) {
            LOGGER.warn("No webhook URL configured. Edit config/{}.yml", MOD_ID);
        }
        // Flush queued entries before the JVM goes away.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CommandBridge.shutdown());
        LOGGER.info("AntiAdminAbuse (Fabric) initialised.");
    }

    private AbuseConfig readConfig() {
        AbuseConfig cfg = new AbuseConfig();
        try {
            Path file = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".yml");
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                try (InputStream in = AntiAdminAbuseFabric.class.getClassLoader()
                        .getResourceAsStream("antiadminabuse-config.yml")) {
                    if (in != null) Files.copy(in, file);
                }
            }
            if (!Files.exists(file)) return cfg;
            return SimpleYaml.parse(Files.readAllLines(file, StandardCharsets.UTF_8)).toConfig(cfg);
        } catch (IOException e) {
            LOGGER.warn("Could not read config; using defaults.", e);
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
