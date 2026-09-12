package enzonic.antiadminabuse.neoforge;

import com.mojang.brigadier.ParseResults;
import enzonic.antiadminabuse.core.AbuseConfig;
import enzonic.antiadminabuse.core.AbuseService;
import enzonic.antiadminabuse.core.CommandRecord;
import enzonic.antiadminabuse.core.Log;
import enzonic.antiadminabuse.core.SimpleYaml;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * NeoForge entry point.
 *
 * NeoForge is the one loader that provides a first-class command hook,
 * {@link CommandEvent}, so no mixin is needed here -- it fires with the parsed
 * command before dispatch. As with Fabric, the build is tied to the Minecraft
 * version it was compiled against.
 */
@Mod(AntiAdminAbuseNeoForge.MOD_ID)
public final class AntiAdminAbuseNeoForge {

    public static final String MOD_ID = "antiadminabuse";
    private static final Logger LOGGER = LoggerFactory.getLogger("AntiAdminAbuse");

    private final AbuseService service;

    public AntiAdminAbuseNeoForge() {
        this.service = new AbuseService(new Slf4jLog(LOGGER), readConfig());
        this.service.start();
        NeoForge.EVENT_BUS.register(this);

        if (!service.config().hasUsableWebhook()) {
            LOGGER.warn("No webhook URL configured. Edit config/{}.yml", MOD_ID);
        }
        LOGGER.info("AntiAdminAbuse (NeoForge) initialised.");
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        try {
            ParseResults<CommandSourceStack> parse = event.getParseResults();
            if (parse == null) return;
            CommandSourceStack source = parse.getContext().getSource();
            String command = parse.getReader().getString();

            String name = source.getTextName();
            String uuid = null;
            String location = null;
            CommandRecord.Source kind;

            ServerPlayer player = source.getPlayer();
            if (player != null) {
                uuid = player.getStringUUID();
                kind = CommandRecord.Source.PLAYER;
                try {
                    // ResourceKey.location() became identifier() when
                    // ResourceLocation was renamed to Identifier.
                    location = source.getLevel().dimension().identifier().toString();
                } catch (Throwable ignored) {
                    // A dimension lookup is not worth failing a log line over.
                }
            } else {
                kind = CommandRecord.Source.CONSOLE;
            }
            // Numeric permission levels were replaced by named permissions;
            // COMMANDS_GAMEMASTER is what the old hasPermission(2) meant. The
            // console holds every permission, so this covers it without a branch.
            boolean privileged = source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);

            service.record(new CommandRecord(name, uuid, kind, withSlash(command), location,
                    privileged, System.currentTimeMillis()));
        } catch (Throwable t) {
            // This is an observer, never a gate: never break the player's command.
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        service.stop();
    }

    private static String withSlash(String command) {
        if (command == null || command.isEmpty()) return "";
        return command.startsWith("/") ? command : "/" + command;
    }

    private AbuseConfig readConfig() {
        AbuseConfig cfg = new AbuseConfig();
        try {
            Path file = FMLPaths.CONFIGDIR.get().resolve(MOD_ID + ".yml");
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                try (InputStream in = AntiAdminAbuseNeoForge.class.getClassLoader()
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
