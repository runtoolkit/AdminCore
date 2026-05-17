package com.legends11.admincore.net;

import com.legends11.admincore.AdminCoreMod;
import com.legends11.admincore.data.LoadedScreen;
import com.legends11.admincore.data.LoadedScreenButton;
import com.legends11.admincore.data.LoadedScreenElement;
import com.legends11.admincore.security.PendingApproval;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * All network channels used by AdminCore.
 *
 * <pre>
 *   admincore:open_confirmation   S→C  Approval screen payload
 *   admincore:approval_response   C→S  Player approve/deny
 *   admincore:open_screen         S→C  Custom GUI screen payload
 *   admincore:screen_action       C→S  Screen button/submit action
 * </pre>
 */
public class AdminCoreNetwork {

    public static final Identifier OPEN_CONFIRMATION = id("open_confirmation");
    public static final Identifier APPROVAL_RESPONSE  = id("approval_response");
    public static final Identifier OPEN_SCREEN        = id("open_screen");
    public static final Identifier SCREEN_ACTION      = id("screen_action");

    private AdminCoreNetwork() {}

    public static void registerServer() {
        // C→S: player responded to approval screen
        ServerPlayNetworking.registerGlobalReceiver(APPROVAL_RESPONSE, (server, player, handler, buf, responseSender) -> {
            String approvalId = buf.readString();
            boolean approved  = buf.readBoolean();

            server.execute(() -> {
                var approvals = AdminCoreMod.approvals();
                var maybe     = approvals.get(approvalId);
                if (maybe.isEmpty()) {
                    AdminCoreMod.LOGGER.warn("Received stale approval response {} from {}",
                            approvalId, player.getName().getString());
                    return;
                }

                PendingApproval pending = maybe.get();

                // Peer mode: the requester cannot approve their own command
                if (pending.mode() == PendingApproval.ApprovalMode.PEER
                        && pending.requester().equals(player.getName().getString())) {
                    player.sendMessage(net.minecraft.text.Text.literal(
                        "You cannot approve your own command. Another op must confirm."));
                    return;
                }

                if (approved) {
                    approvals.approve(approvalId);
                    AdminCoreMod.LOGGER.info("Approval granted by {} for '{}'",
                            player.getName().getString(), pending.command());
                } else {
                    approvals.remove(approvalId);
                    AdminCoreMod.LOGGER.info("Approval denied by {} for '{}'",
                            player.getName().getString(), pending.command());
                }
            });
        });

        // C→S: screen form submitted or button clicked
        ServerPlayNetworking.registerGlobalReceiver(SCREEN_ACTION, (server, player, handler, buf, responseSender) -> {
            String screenIdStr   = buf.readString();
            String actionType    = buf.readString();
            String actionValue   = buf.readString();
            String screenStorage = buf.readString();  // may be empty
            int inputCount       = buf.readInt();
            Map<String, String> inputs = new HashMap<>();
            for (int i = 0; i < inputCount; i++) {
                String k = buf.readString();
                String v = buf.readString();
                inputs.put(k, v);
            }

            server.execute(() -> handleScreenAction(player, screenIdStr, actionType,
                                                     actionValue, screenStorage, inputs));
        });
    }

    // ── Screen action handler ─────────────────────────────────────────────────

    private static void handleScreenAction(ServerPlayerEntity player,
                                           String screenIdStr, String actionType,
                                           String actionValue, String screenStorage,
                                           Map<String, String> inputs) {
        var packs     = AdminCoreMod.packs();
        var policy    = AdminCoreMod.policy();
        var approvals = AdminCoreMod.approvals();

        AdminCoreMod.LOGGER.info("Screen action: screen={} type={} value={} inputs={}",
                screenIdStr, actionType, actionValue, inputs.size());

        // Resolve the storage to write inputs into.
        // Priority: element-level storage > screen storage_target > function default
        Identifier storageId = null;
        if (!screenStorage.isBlank()) {
            storageId = parseId(screenStorage);
        }

        // Write submitted inputs to storage
        if (!inputs.isEmpty() && storageId != null) {
            packs.writeToStorage(storageId, inputs);
        }

        switch (actionType) {
            case "function" -> {
                if (actionValue.isBlank()) {
                    AdminCoreMod.LOGGER.warn("Screen action 'function' has no action_value");
                    return;
                }
                Identifier fnId = parseId(actionValue);

                // If the function has no storage and a screen storage exists, inject it
                Identifier overrideStorage = storageId;

                try {
                    var source = AdminCoreMod.server().getCommandSource();
                    packs.executeFunction(fnId, source, policy, approvals, overrideStorage);
                } catch (com.legends11.admincore.data.PackManager.DangerousCommandException e) {
                    // Approval screen already sent
                } catch (Exception e) {
                    AdminCoreMod.LOGGER.error("Failed running screen function {}: {}", fnId, e.getMessage());
                }
            }

            case "open_screen" -> {
                if (actionValue.isBlank()) return;
                packs.screen(parseId(actionValue)).ifPresent(s -> sendScreen(player, s));
            }

            case "confirm" -> {
                // actionValue = approvalId
                AdminCoreMod.approvals().get(actionValue).ifPresent(a -> {
                    AdminCoreMod.approvals().approve(actionValue);
                    AdminCoreMod.LOGGER.info("Screen-confirmed approval {} by {}",
                            actionValue, player.getName().getString());
                });
            }

            default -> AdminCoreMod.LOGGER.warn("Unknown screen action type: {}", actionType);
        }
    }

    // ── Send helpers ──────────────────────────────────────────────────────────

    /** Sends the approval confirmation screen to a player. */
    public static void sendApprovalScreen(ServerPlayerEntity player, PendingApproval approval) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(approval.id());
        buf.writeString(approval.requester());
        buf.writeString(approval.origin());
        buf.writeString(approval.command());
        buf.writeString(approval.explanation());
        buf.writeString(approval.mode().name());
        ServerPlayNetworking.send(player, OPEN_CONFIRMATION, buf);
    }

    /** Serialises and sends a custom screen to a player. */
    public static void sendScreen(ServerPlayerEntity player, LoadedScreen screen) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(screen.id().toString());
        buf.writeString(screen.title());
        buf.writeInt(screen.rows());
        buf.writeString(screen.storageTarget() != null ? screen.storageTarget().toString() : "");

        // Elements
        buf.writeInt(screen.elements().size());
        for (LoadedScreenElement el : screen.elements()) {
            buf.writeString(el.elementType());
            buf.writeString(el.key());
            buf.writeString(el.label());
            buf.writeString(el.tooltip() != null ? el.tooltip() : "");
            buf.writeString(el.defaultValue() != null ? el.defaultValue() : "");
            buf.writeString(el.min() != null ? el.min() : "");
            buf.writeString(el.max() != null ? el.max() : "");
            buf.writeString(el.actionType() != null ? el.actionType() : "");
            buf.writeString(el.actionValue() != null ? el.actionValue() : "");
            buf.writeString(el.storageTarget() != null ? el.storageTarget().toString() : "");
        }

        // Legacy slot buttons
        buf.writeInt(screen.buttons().size());
        for (LoadedScreenButton btn : screen.buttons()) {
            buf.writeInt(btn.slot());
            buf.writeString(btn.label());
            buf.writeString(btn.itemId());
            buf.writeString(btn.actionType());
            buf.writeString(btn.actionValue());
            buf.writeString(btn.tooltip());
        }

        ServerPlayNetworking.send(player, OPEN_SCREEN, buf);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static Identifier id(String path) {
        return Identifier.of(AdminCoreMod.MOD_ID, path);
    }

    private static Identifier parseId(String raw) {
        if (raw.contains(":")) {
            String[] p = raw.split(":", 2);
            return Identifier.of(p[0], p[1]);
        }
        return Identifier.of("minecraft", raw);
    }
}
