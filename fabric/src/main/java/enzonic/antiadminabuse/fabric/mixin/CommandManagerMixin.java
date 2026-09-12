package enzonic.antiadminabuse.fabric.mixin;

import enzonic.antiadminabuse.fabric.CommandBridge;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts server command dispatch.
 *
 * Fabric exposes no command-execution event, so a mixin is the supported route.
 * Only the outer entry point is hooked, under both names Minecraft has used for
 * it -- {@code parseAndExecute} in 1.21.11, {@code executeWithPrefix} in earlier
 * releases. Exactly one of those exists in any given build, so both are declared
 * {@code require = 0} to let the build succeed either way.
 *
 * Injecting at HEAD means the record is written before the command runs, so a
 * command that crashes the server is still logged. The trade-off is that a
 * command later rejected for bad syntax or permissions is logged too, which for
 * an audit trail is the right side to err on.
 */
@Mixin(CommandManager.class)
public abstract class CommandManagerMixin {

    @Inject(method = "parseAndExecute", at = @At("HEAD"), require = 0)
    private void aaa$onParseAndExecute(ServerCommandSource source, String command, CallbackInfo ci) {
        CommandBridge.record(source, withSlash(command));
    }

    @Inject(method = "executeWithPrefix", at = @At("HEAD"), require = 0)
    private void aaa$onExecuteWithPrefix(ServerCommandSource source, String command, CallbackInfo ci) {
        CommandBridge.record(source, withSlash(command));
    }

    /** Minecraft passes the command without a leading slash; restore it. */
    private static String withSlash(String command) {
        if (command == null || command.isEmpty()) return "";
        return command.startsWith("/") ? command : "/" + command;
    }
}
