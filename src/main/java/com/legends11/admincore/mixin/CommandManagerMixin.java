package com.legends11.admincore.mixin;

import com.legends11.admincore.AdminCoreMod;
import com.legends11.admincore.net.AdminCoreNetwork;
import com.legends11.admincore.security.CommandApprovalManager;
import com.legends11.admincore.security.CommandSafetyPolicy;
import com.legends11.admincore.security.PendingApproval;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

@Mixin(CommandManager.class)
public class CommandManagerMixin {

    /**
     * Intercepts every command before execution.
     * If the bypass flag is set (we're executing an approved command),
     * let it through immediately. Otherwise, run safety checks.
     */
    @Inject(method = "executeWithPrefix", at = @At("HEAD"), cancellable = true)
    private void admincore$onExecute(ServerCommandSource source, String command,
                                      CallbackInfoReturnable<Integer> cir) {
        CommandApprovalManager approvals = AdminCoreMod.approvals();
        CommandSafetyPolicy policy = AdminCoreMod.policy();

        // Let approved executions through
        if (approvals.isBypassActive()) return;

        String trimmed = command.trim();

        // Skip function dispatch lines (handled separately in PackManager)
        if (trimmed.startsWith("function ")) return;

        CommandSafetyPolicy.CheckResult result = policy.check(trimmed);
        if (!result.dangerous()) return;

        // Check if this exact command was pre-approved
        if (approvals.consumeApproval(trimmed)) return;

        // Block and create approval request
        ServerPlayerEntity player = source.getPlayer();
        String requesterName = player != null ? player.getName().getString() : source.getName();

        // Peer mode for highest-risk ops; self for the rest
        PendingApproval.ApprovalMode mode = isPeerRequired(trimmed)
                ? PendingApproval.ApprovalMode.PEER
                : PendingApproval.ApprovalMode.SELF;

        PendingApproval approval = approvals.create(
                requesterName,
                "command",
                trimmed,
                result.explanation(),
                mode);

        AdminCoreMod.LOGGER.warn("Blocked dangerous command '{}' from {} ({}). approval={}",
                trimmed, requesterName, result.explanation(), approval.id());

        // Send approval screen to the issuing player if they're online
        if (player != null) {
            AdminCoreNetwork.sendApprovalScreen(player, approval);
        }

        cir.setReturnValue(0);
    }

    /** op/deop/ban/stop/restart require a different player to confirm. */
    private boolean isPeerRequired(String cmd) {
        String lower = cmd.toLowerCase(Locale.ROOT).replaceFirst("^/", "").trim();
        return lower.startsWith("op ")
            || lower.startsWith("deop ")
            || lower.startsWith("stop")
            || lower.startsWith("restart");
    }
}
