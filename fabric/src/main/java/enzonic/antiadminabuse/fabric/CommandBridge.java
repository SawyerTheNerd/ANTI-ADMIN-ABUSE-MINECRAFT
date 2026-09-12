package enzonic.antiadminabuse.fabric;

import enzonic.antiadminabuse.core.AbuseService;
import enzonic.antiadminabuse.core.CommandRecord;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Bridge between the mixin and the core service.
 *
 * A mixin class is transformed and cannot hold ordinary state, so the service
 * reference lives here.
 *
 * There is no de-duplication on purpose. The mixin hooks two method names, but
 * they are the same Minecraft method under the names it has carried in different
 * versions, so only one can exist in any given build. Guarding against a
 * hypothetical double-log would mean dropping commands that merely look alike,
 * and for an audit trail a visible duplicate is a far smaller problem than a
 * silently missing entry.
 */
public final class CommandBridge {

    private static volatile AbuseService service;

    private CommandBridge() {}

    public static void install(AbuseService created) {
        service = created;
    }

    public static void shutdown() {
        AbuseService current = service;
        if (current != null) current.stop();
    }

    /**
     * Records one command. Never throws: a failure here must not break the
     * command the player actually typed.
     */
    public static void record(ServerCommandSource source, String command) {
        AbuseService current = service;
        if (current == null || source == null || command == null) return;
        try {
            String name = source.getName();
            String uuid = null;
            String location = null;
            CommandRecord.Source kind;

            ServerPlayerEntity player = source.getPlayer();
            boolean privileged;
            if (player != null) {
                uuid = player.getUuidAsString();
                kind = CommandRecord.Source.PLAYER;
                try {
                    location = source.getWorld().getRegistryKey().getValue().toString();
                } catch (Throwable ignored) {
                    // A world lookup is not worth failing a log line over.
                }
                // Minecraft 1.21.11 replaced numeric permission levels with a
                // permission object, so ask the operator list directly instead.
                privileged = source.getServer().getPlayerManager()
                        .isOperator(player.getPlayerConfigEntry());
            } else {
                kind = CommandRecord.Source.CONSOLE;
                // Console and command blocks are inherently privileged.
                privileged = true;
            }

            current.record(new CommandRecord(name, uuid, kind, command, location, privileged,
                    System.currentTimeMillis()));
        } catch (Throwable t) {
            // Swallowed deliberately: this is an observer, never a gate.
        }
    }
}
