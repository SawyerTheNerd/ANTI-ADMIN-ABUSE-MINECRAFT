package enzonic.antiadminabuse.bukkit;

import enzonic.antiadminabuse.core.AbuseService;
import enzonic.antiadminabuse.core.CommandRecord;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Observes command execution and forwards it to the core service.
 *
 * Both handlers run at {@link EventPriority#MONITOR} with {@code ignoreCancelled},
 * so the log reflects commands that actually ran rather than ones another plugin
 * blocked, and this listener never influences the outcome of an event.
 */
public final class CommandWatcher implements Listener {

    /** Marks a player as staff for the "only-staff-commands" filter. */
    public static final String STAFF_PERMISSION = "antiadminabuse.staff";

    private final JavaPlugin plugin;
    private final AbuseService service;

    public CommandWatcher(JavaPlugin plugin, AbuseService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String world = null;
        try {
            world = player.getWorld() == null ? null : player.getWorld().getName();
        } catch (Throwable ignored) {
            // Extremely defensive: some forks can throw here for a disconnecting player.
        }
        service.record(new CommandRecord(
                player.getName(),
                player.getUniqueId() == null ? null : player.getUniqueId().toString(),
                CommandRecord.Source.PLAYER,
                event.getMessage(),
                world,
                isPrivileged(player),
                System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        CommandSender sender = event.getSender();
        boolean rcon = sender instanceof RemoteConsoleCommandSender;
        service.record(new CommandRecord(
                sender.getName(),
                null,
                rcon ? CommandRecord.Source.RCON : CommandRecord.Source.CONSOLE,
                // ServerCommandEvent omits the leading slash; restore it so the
                // Discord output matches what a player-run command looks like.
                prefixSlash(event.getCommand()),
                null,
                true,
                System.currentTimeMillis()));
    }

    private static String prefixSlash(String command) {
        if (command == null) return "";
        String trimmed = command.trim();
        if (trimmed.isEmpty()) return "";
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static boolean isPrivileged(Player player) {
        return player.isOp() || player.hasPermission(STAFF_PERMISSION);
    }
}
