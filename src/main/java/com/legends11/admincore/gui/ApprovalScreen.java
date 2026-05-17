package com.legends11.admincore.gui;

import com.legends11.admincore.net.AdminCoreNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

/**
 * Client-side screen shown when a dangerous command needs approval.
 *
 * Layout:
 *   Title bar — "Approve Command?"
 *   Command box (grey bg, truncated if long)
 *   Explanation label
 *   Origin label ("Requested by X via Y")
 *   Peer warning (if mode=PEER)
 *   [Approve]  [Deny]
 */
public class ApprovalScreen extends Screen {

    private static final int BG_COLOR      = 0xCC1A1A2E;
    private static final int BORDER_COLOR  = 0xFF4A90D9;
    private static final int CMD_BG        = 0xFF0D0D1A;
    private static final int TEXT_COLOR    = 0xFFE0E0E0;
    private static final int WARN_COLOR    = 0xFFFF9944;
    private static final int DENY_COLOR    = 0xFFCC3333;
    private static final int APPROVE_COLOR = 0xFF33AA55;

    private final String approvalId;
    private final String requester;
    private final String origin;
    private final String command;
    private final String explanation;
    private final boolean isPeer;

    public ApprovalScreen(String approvalId, String requester, String origin,
                          String command, String explanation, String modeName) {
        super(Text.translatable("screen.admincore.approval.title"));
        this.approvalId  = approvalId;
        this.requester   = requester;
        this.origin      = origin;
        this.command     = command;
        this.explanation = explanation;
        this.isPeer      = "PEER".equals(modeName);
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int btnY = height / 2 + 50;

        // Approve button
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("screen.admincore.approval.approve"),
                        btn -> sendResponse(true))
                .dimensions(cx - 110, btnY, 100, 20)
                .build());

        // Deny button
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("screen.admincore.approval.deny"),
                        btn -> sendResponse(false))
                .dimensions(cx + 10, btnY, 100, 20)
                .build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);

        int cx = width / 2;
        int panelW = Math.min(400, width - 40);
        int panelX = cx - panelW / 2;
        int panelY = height / 2 - 80;

        // Panel background
        ctx.fill(panelX, panelY, panelX + panelW, panelY + 160, BG_COLOR);
        ctx.drawBorder(panelX, panelY, panelW, 160, BORDER_COLOR);

        // Title
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("screen.admincore.approval.title"),
                cx, panelY + 8, 0xFFFFFFFF);

        // Command box
        int cmdBoxY = panelY + 24;
        ctx.fill(panelX + 8, cmdBoxY, panelX + panelW - 8, cmdBoxY + 16, CMD_BG);
        String displayCmd = command.length() > 60 ? command.substring(0, 57) + "..." : command;
        ctx.drawTextWithShadow(textRenderer, displayCmd, panelX + 12, cmdBoxY + 4, 0xFFFFFF44);

        // Explanation
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Reason: " + explanation),
                cx, cmdBoxY + 22, WARN_COLOR);

        // Origin / requester
        String originLine = "Requested by " + requester + " via " + origin;
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(originLine), cx, cmdBoxY + 36, TEXT_COLOR);

        // Peer warning
        if (isPeer) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("⚠ A different op must approve this command."),
                    cx, cmdBoxY + 52, WARN_COLOR);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() { return false; }

    private void sendResponse(boolean approved) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(approvalId);
        buf.writeBoolean(approved);
        ClientPlayNetworking.send(AdminCoreNetwork.APPROVAL_RESPONSE, buf);
        close();
    }
}
