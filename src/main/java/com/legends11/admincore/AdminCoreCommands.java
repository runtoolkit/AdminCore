package com.legends11.admincore;

import com.legends11.admincore.data.PackManager;
import com.legends11.admincore.security.CommandApprovalManager;
import com.legends11.admincore.security.CommandSafetyPolicy;
import com.legends11.admincore.security.PendingApproval;
import com.legends11.admincore.net.AdminCoreNetwork;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Optional;

public class AdminCoreCommands {

    public static void register(CommandSafetyPolicy policy, CommandApprovalManager approvals, PackManager packs) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerAll(dispatcher, policy, approvals, packs));
    }

    private static void registerAll(CommandDispatcher<ServerCommandSource> d,
                                    CommandSafetyPolicy policy,
                                    CommandApprovalManager approvals,
                                    PackManager packs) {
        d.register(CommandManager.literal("admincore")
            .requires(src -> src.hasPermissionLevel(2))

            // /admincore reload
            .then(CommandManager.literal("reload")
                .executes(ctx -> {
                    packs.reload(ctx.getSource().getServer());
                    ctx.getSource().sendFeedback(() -> Text.literal(
                            "AdminCore reloaded. Functions=" + packs.functionCount()
                            + ", Storages=" + packs.storageCount()
                            + ", Screens=" + packs.screenCount()), true);
                    return 1;
                }))

            // /admincore status
            .then(CommandManager.literal("status")
                .executes(ctx -> {
                    int pending = approvals.pendingCount();
                    ctx.getSource().sendFeedback(() -> Text.literal(
                            "Functions=" + packs.functionCount()
                            + " Storages=" + packs.storageCount()
                            + " Screens=" + packs.screenCount()
                            + " PendingApprovals=" + pending), false);
                    return 1;
                }))

            // /admincore function <id>
            .then(CommandManager.literal("function")
                .then(CommandManager.argument("id", IdentifierArgumentType.identifier())
                    .suggests((ctx, builder) -> {
                        packs.functionIds().forEach(id -> builder.suggest(id.toString()));
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        Identifier id = IdentifierArgumentType.getIdentifier(ctx, "id");
                        ServerCommandSource source = ctx.getSource();
                        try {
                            packs.executeFunction(id, source, policy, approvals);
                        } catch (PackManager.DangerousCommandException e) {
                            // Approval screen already sent by executeFunction
                        } catch (Exception e) {
                            source.sendError(Text.literal("Function error: " + e.getMessage()));
                            AdminCoreMod.LOGGER.error("Function execution failed for {}", id, e);
                        }
                        return 1;
                    })))

            // /admincore screen open <id>
            .then(CommandManager.literal("screen")
                .then(CommandManager.literal("open")
                    .then(CommandManager.argument("id", IdentifierArgumentType.identifier())
                        .suggests((ctx, builder) -> {
                            packs.screenIds().forEach(id -> builder.suggest(id.toString()));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            Identifier id = IdentifierArgumentType.getIdentifier(ctx, "id");
                            ServerCommandSource source = ctx.getSource();
                            ServerPlayerEntity player = source.getPlayer();
                            if (player == null) {
                                source.sendError(Text.literal("Must be a player."));
                                return 0;
                            }
                            packs.screen(id).ifPresentOrElse(
                                screen -> AdminCoreNetwork.sendScreen(player, screen),
                                () -> source.sendError(Text.literal("Unknown screen: " + id))
                            );
                            return 1;
                        }))))

            // /admincore approve <approvalId>
            .then(CommandManager.literal("approve")
                .then(CommandManager.argument("approvalId", StringArgumentType.string())
                    .suggests((ctx, builder) -> {
                        approvals.pendingIds().forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(ctx -> executeApprove(ctx, approvals, true))))

            // /admincore deny <approvalId>
            .then(CommandManager.literal("deny")
                .then(CommandManager.argument("approvalId", StringArgumentType.string())
                    .suggests((ctx, builder) -> {
                        approvals.pendingIds().forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(ctx -> executeApprove(ctx, approvals, false))))

            // /admincore give wand
            .then(CommandManager.literal("give")
                .then(CommandManager.literal("wand")
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                        if (player == null) {
                            ctx.getSource().sendError(Text.literal("Must be a player."));
                            return 0;
                        }
                        player.getInventory().insertStack(
                            com.legends11.admincore.item.AdminCoreItems.WAND.getDefaultStack());
                        ctx.getSource().sendFeedback(() -> Text.literal("Issued AdminCore Wand."), false);
                        return 1;
                    })))

            // /admincore broadcast <message>
            .then(CommandManager.literal("broadcast")
                .then(CommandManager.argument("message", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String msg = StringArgumentType.getString(ctx, "message");
                        String senderName = ctx.getSource().getName();
                        Text text = Text.literal("[Broadcast] " + senderName + ": " + msg);
                        ctx.getSource().getServer().getPlayerManager().getPlayerList()
                            .forEach(p -> p.sendMessage(text));
                        ctx.getSource().sendFeedback(() -> Text.literal(
                            "Broadcast sent to " + ctx.getSource().getServer()
                                .getPlayerManager().getCurrentPlayerCount() + " player(s)."), false);
                        return 1;
                    })))
        );
    }

    private static int executeApprove(CommandContext<ServerCommandSource> ctx,
                                      CommandApprovalManager approvals, boolean approve) {
        String approvalId = StringArgumentType.getString(ctx, "approvalId");
        ServerCommandSource source = ctx.getSource();

        Optional<PendingApproval> maybe = approvals.get(approvalId);
        if (maybe.isEmpty()) {
            source.sendError(Text.literal("No pending approval with id: " + approvalId));
            return 0;
        }

        PendingApproval pending = maybe.get();
        if (pending.isExpired()) {
            approvals.remove(approvalId);
            source.sendError(Text.literal("Approval expired."));
            return 0;
        }

        if (approve) {
            approvals.approve(approvalId);
            AdminCoreMod.LOGGER.info("Approved '{}' via /admincore approve (by {})",
                    pending.command(), source.getName());
            source.sendFeedback(() -> Text.literal("Approved: " + pending.command()), true);
        } else {
            approvals.remove(approvalId);
            AdminCoreMod.LOGGER.info("Denied '{}' via /admincore deny (by {})",
                    pending.command(), source.getName());
            source.sendFeedback(() -> Text.literal("Denied: " + pending.command()), true);
        }
        return 1;
    }
}
