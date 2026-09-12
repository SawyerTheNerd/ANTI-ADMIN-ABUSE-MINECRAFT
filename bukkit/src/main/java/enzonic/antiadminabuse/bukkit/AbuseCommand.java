package enzonic.antiadminabuse.bukkit;

import enzonic.antiadminabuse.core.AbuseConfig;
import enzonic.antiadminabuse.core.AbuseService;
import enzonic.antiadminabuse.core.CommandRecord;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Handles {@code /antiadminabuse} (alias {@code /aaa}) and legacy {@code /setwebhook}. */
public final class AbuseCommand implements CommandExecutor, TabCompleter {

    public static final String ADMIN_PERMISSION = "antiadminabuse.admin";

    private static final List<String> SUBCOMMANDS =
            Collections.unmodifiableList(Arrays.asList("setwebhook", "reload", "status", "test"));

    private final AntiAdminAbusePlugin plugin;
    private final AbuseService service;

    public AbuseCommand(AntiAdminAbusePlugin plugin, AbuseService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Checked for every sender type. The console holds all permissions, so this
        // is stricter than the old "only verify for players" check without locking
        // anyone legitimate out.
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            reply(sender, ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        // Legacy form: /setwebhook <url> with no subcommand.
        String[] effective = args;
        if (command.getName().equalsIgnoreCase("setwebhook")) {
            effective = new String[args.length + 1];
            effective[0] = "setwebhook";
            System.arraycopy(args, 0, effective, 1, args.length);
        }

        if (effective.length == 0) {
            usage(sender, label);
            return true;
        }

        String sub = effective[0].toLowerCase(Locale.ROOT);
        if (sub.equals("setwebhook")) {
            return setWebhook(sender, effective);
        }
        if (sub.equals("reload")) {
            plugin.reload();
            reply(sender, ChatColor.GREEN + "Configuration reloaded.");
            for (String problem : service.config().validate()) {
                reply(sender, ChatColor.YELLOW + problem);
            }
            return true;
        }
        if (sub.equals("status")) {
            return status(sender);
        }
        if (sub.equals("test")) {
            return test(sender);
        }
        usage(sender, label);
        return true;
    }

    private boolean setWebhook(CommandSender sender, String[] effective) {
        if (effective.length != 2) {
            reply(sender, ChatColor.YELLOW + "Usage: /aaa setwebhook <url>");
            return true;
        }
        String url = effective[1].trim();
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            reply(sender, ChatColor.RED + "That does not look like a URL. Paste the full Discord webhook URL.");
            return true;
        }
        plugin.saveWebhookUrl(url);
        // Never echo the URL back: it is a bearer credential, and in chat it would
        // be visible over the shoulder or in any chat-logging plugin.
        reply(sender, ChatColor.GREEN + "Webhook URL saved. Run /aaa test to verify it works.");
        return true;
    }

    private boolean status(CommandSender sender) {
        AbuseConfig cfg = service.config();
        reply(sender, ChatColor.AQUA + "AntiAdminAbuse " + plugin.getDescription().getVersion());
        reply(sender, " enabled: " + cfg.enabled);
        reply(sender, " webhook configured: " + cfg.hasUsableWebhook());
        reply(sender, " player commands: " + cfg.logPlayerCommands
                + ", console commands: " + cfg.logConsoleCommands);
        reply(sender, " staff-only filter: " + cfg.onlyPrivileged);
        reply(sender, " watched: " + (cfg.watchedCommands.isEmpty()
                ? "all (minus ignore list)" : String.valueOf(cfg.watchedCommands.size()) + " command(s)"));
        reply(sender, " delivered: " + service.sender().deliveredCount()
                + ", failed: " + service.sender().failedCount()
                + ", dropped: " + service.sender().droppedCount()
                + ", queued: " + service.sender().queueDepth());
        return true;
    }

    private boolean test(CommandSender sender) {
        if (!service.config().hasUsableWebhook()) {
            reply(sender, ChatColor.RED + "No webhook URL is configured. Run /aaa setwebhook <url> first.");
            return true;
        }
        long before = service.sender().deliveredCount();
        service.sender().enqueue(AbuseService.buildPayload(new CommandRecord(
                sender.getName(), null, CommandRecord.Source.CONSOLE,
                "/aaa test (connectivity check)", null, true, System.currentTimeMillis()),
                service.config()));
        reply(sender, ChatColor.GREEN + "Test message queued (delivered so far: " + before
                + "). Check your Discord channel, then /aaa status for the result.");
        return true;
    }

    private void usage(CommandSender sender, String label) {
        reply(sender, ChatColor.AQUA + "/" + label + " setwebhook <url>" + ChatColor.GRAY + " - set the Discord webhook");
        reply(sender, ChatColor.AQUA + "/" + label + " reload" + ChatColor.GRAY + " - re-read config.yml");
        reply(sender, ChatColor.AQUA + "/" + label + " status" + ChatColor.GRAY + " - show settings and delivery counters");
        reply(sender, ChatColor.AQUA + "/" + label + " test" + ChatColor.GRAY + " - send a test message");
    }

    /** Strips colour codes for non-player senders so consoles stay readable. */
    private static void reply(CommandSender sender, String message) {
        if (sender instanceof Player) {
            sender.sendMessage(message);
        } else {
            sender.sendMessage(ChatColor.stripColor(message));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) return Collections.emptyList();
        if (args.length == 1) {
            List<String> matches = new ArrayList<String>();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) matches.add(sub);
            }
            return matches;
        }
        // Never suggest anything for the URL argument.
        return Collections.emptyList();
    }
}
