package com.legends11.admincore;

import com.legends11.admincore.gui.ApprovalScreen;
import com.legends11.admincore.gui.AdminScreen;
import com.legends11.admincore.net.AdminCoreNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class AdminCoreClient implements ClientModInitializer {

    public static KeyBinding openControlKey;

    @Override
    public void onInitializeClient() {
        // Keybinding: default unbound
        openControlKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.admincore.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "category.admincore"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openControlKey.wasPressed()) {
                if (client.player != null) {
                    // Ask server to open the control screen
                    // (server validates permission server-side)
                    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                        .send(AdminCoreNetwork.SCREEN_ACTION,
                              buildOpenControlBuf());
                }
            }
        });

        // S→C: Approval screen
        ClientPlayNetworking.registerGlobalReceiver(AdminCoreNetwork.OPEN_CONFIRMATION,
                (client, handler, buf, responseSender) -> {
                    String approvalId   = buf.readString();
                    String requester    = buf.readString();
                    String origin       = buf.readString();
                    String command      = buf.readString();
                    String explanation  = buf.readString();
                    String modeName     = buf.readString();

                    client.execute(() ->
                        client.setScreen(new ApprovalScreen(
                            approvalId, requester, origin,
                            command, explanation, modeName)));
                });

        // S→C: Custom GUI screen
        ClientPlayNetworking.registerGlobalReceiver(AdminCoreNetwork.OPEN_SCREEN,
                (client, handler, buf, responseSender) -> {
                    AdminScreen screen = AdminScreen.deserialize(buf);
                    client.execute(() -> client.setScreen(screen));
                });
    }

    private net.minecraft.network.PacketByteBuf buildOpenControlBuf() {
        var buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        buf.writeString("admincore:control");  // screenId
        buf.writeString("open_screen");         // actionType
        buf.writeString("admincore:control");   // actionValue
        buf.writeString("");                    // storageId
        buf.writeInt(0);                        // no inputs
        return buf;
    }
}
