package com.legends11.admincore.gui;

import com.legends11.admincore.net.AdminCoreNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

import java.util.*;

/**
 * Client-side rendering of a datapack-defined AdminCore screen.
 *
 * <p>Each element with a non-empty {@code key} contributes to the submitted
 * inputs map. Element-level {@code storageTarget} overrides the screen-level
 * one for that key when sending the action.</p>
 */
public class AdminScreen extends Screen {

    // ── Data ──────────────────────────────────────────────────────────────────

    private final String screenId;
    private final String storageTarget;     // screen-level, may be empty
    private final List<ElementData> elements;
    private final List<ButtonData> buttons;

    // Runtime widgets (parallel to elements list)
    private final List<TextFieldWidget> textFields    = new ArrayList<>();
    private final List<CheckboxWidget>  checkboxes    = new ArrayList<>();

    // Colour constants
    private static final int BG_PANEL   = 0xCC1A1A2E;
    private static final int BG_BORDER  = 0xFF4A90D9;
    private static final int BG_INPUT   = 0xFF0D0D1A;
    private static final int COL_LABEL  = 0xFFCCCCCC;
    private static final int COL_TITLE  = 0xFFFFFFFF;

    // ── Construction (server packet → client object) ──────────────────────────

    private AdminScreen(String screenId, String title, String storageTarget,
                        List<ElementData> elements, List<ButtonData> buttons) {
        super(Text.literal(title));
        this.screenId      = screenId;
        this.storageTarget = storageTarget;
        this.elements      = elements;
        this.buttons       = buttons;
    }

    /** Deserialises from the network packet written by {@code AdminCoreNetwork.sendScreen}. */
    public static AdminScreen deserialize(PacketByteBuf buf) {
        String id     = buf.readString();
        String title  = buf.readString();
        int rows      = buf.readInt();  // not used for layout here, kept for future use
        String stgt   = buf.readString();

        int eCount = buf.readInt();
        List<ElementData> elements = new ArrayList<>(eCount);
        for (int i = 0; i < eCount; i++) {
            elements.add(new ElementData(
                buf.readString(),  // elementType
                buf.readString(),  // key
                buf.readString(),  // label
                buf.readString(),  // tooltip
                buf.readString(),  // defaultValue
                buf.readString(),  // min
                buf.readString(),  // max
                buf.readString(),  // actionType
                buf.readString(),  // actionValue
                buf.readString()   // storageTarget (element-level)
            ));
        }

        int bCount = buf.readInt();
        List<ButtonData> buttons = new ArrayList<>(bCount);
        for (int i = 0; i < bCount; i++) {
            buttons.add(new ButtonData(
                buf.readInt(),     // slot
                buf.readString(),  // label
                buf.readString(),  // itemId
                buf.readString(),  // actionType
                buf.readString(),  // actionValue
                buf.readString()   // tooltip
            ));
        }

        return new AdminScreen(id, title, stgt, elements, buttons);
    }

    // ── Widget layout ─────────────────────────────────────────────────────────

    @Override
    protected void init() {
        textFields.clear();
        checkboxes.clear();

        int panelW = Math.min(420, width - 40);
        int panelX = width / 2 - panelW / 2;
        int startY = height / 2 - Math.min(200, elements.size() * 28 + 60) / 2 + 20;

        int y = startY;

        for (ElementData el : elements) {
            switch (el.elementType()) {
                case "text" -> {
                    TextFieldWidget field = new TextFieldWidget(
                        textRenderer,
                        panelX + 8, y, panelW - 16, 16,
                        Text.literal(el.label()));
                    field.setMaxLength(256);
                    if (!el.defaultValue().isEmpty()) field.setText(el.defaultValue());
                    field.setPlaceholder(Text.literal(el.label()));
                    addDrawableChild(field);
                    textFields.add(field);
                    y += 28;
                }

                case "number" -> {
                    TextFieldWidget field = new TextFieldWidget(
                        textRenderer,
                        panelX + 8, y, panelW - 16, 16,
                        Text.literal(el.label()));
                    field.setMaxLength(10);
                    field.setText(el.defaultValue().isEmpty() ? "0" : el.defaultValue());
                    // Filter to digits and minus
                    field.setTextPredicate(s -> s.matches("-?\\d*"));
                    addDrawableChild(field);
                    textFields.add(field);
                    y += 28;
                }

                case "checkbox" -> {
                    boolean checked = "true".equalsIgnoreCase(el.defaultValue());
                    CheckboxWidget cb = new CheckboxWidget(
                        panelX + 8, y,
                        panelW - 16, 16,
                        Text.literal(el.label()),
                        checked);
                    addDrawableChild(cb);
                    checkboxes.add(cb);
                    y += 24;
                }

                case "label" -> y += 18;  // label rendered in render(), no widget

                case "button" -> {
                    final ElementData captured = el;
                    addDrawableChild(ButtonWidget.builder(
                            Text.literal(el.label()),
                            btn -> sendAction(captured.actionType(), captured.actionValue(),
                                             captured.storageTarget().isEmpty()
                                                 ? storageTarget : captured.storageTarget()))
                        .dimensions(panelX + 8, y, panelW - 16, 18)
                        .build());
                    y += 26;
                }
            }
        }

        // Submit button (always present at bottom)
        int btnY = y + 8;
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Submit"),
                btn -> submitForm())
            .dimensions(width / 2 - 60, btnY, 60, 20)
            .build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.admincore.close"),
                btn -> close())
            .dimensions(width / 2 + 5, btnY, 55, 20)
            .build());
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);

        int panelW = Math.min(420, width - 40);
        int panelX = width / 2 - panelW / 2;
        int panelH = Math.min(300, elements.size() * 28 + 80);
        int panelY = height / 2 - panelH / 2;

        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, BG_PANEL);
        ctx.drawBorder(panelX, panelY, panelW, panelH, BG_BORDER);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, panelY + 6, COL_TITLE);

        int y = panelY + 20;
        int tfIdx = 0, cbIdx = 0;

        for (ElementData el : elements) {
            switch (el.elementType()) {
                case "text", "number" -> {
                    ctx.drawTextWithShadow(textRenderer, Text.literal(el.label()),
                        panelX + 10, y, COL_LABEL);
                    y += 28;
                    tfIdx++;
                }
                case "checkbox" -> {
                    // label is drawn by the widget itself
                    y += 24;
                    cbIdx++;
                }
                case "label" -> {
                    ctx.drawTextWithShadow(textRenderer, Text.literal(el.label()),
                        panelX + 10, y, COL_LABEL);
                    y += 18;
                }
                case "button" -> y += 26;
            }
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() { return false; }

    // ── Actions ───────────────────────────────────────────────────────────────

    /** Collect all element values and send them with the action. */
    private void submitForm() {
        // Collect inputs
        Map<String, String> inputs = collectInputs();

        // Send with screen-level storage; action is empty (submit)
        PacketByteBuf buf = buildActionPacket("submit", "", storageTarget, inputs);
        ClientPlayNetworking.send(AdminCoreNetwork.SCREEN_ACTION, buf);
        close();
    }

    private void sendAction(String actionType, String actionValue, String effectiveStorage) {
        Map<String, String> inputs = collectInputs();
        PacketByteBuf buf = buildActionPacket(actionType, actionValue, effectiveStorage, inputs);
        ClientPlayNetworking.send(AdminCoreNetwork.SCREEN_ACTION, buf);
        close();
    }

    private Map<String, String> collectInputs() {
        Map<String, String> inputs = new LinkedHashMap<>();
        int tfIdx = 0, cbIdx = 0;

        for (ElementData el : elements) {
            if (!el.key().isEmpty()) {
                switch (el.elementType()) {
                    case "text", "number" -> {
                        if (tfIdx < textFields.size()) {
                            inputs.put(el.key(), textFields.get(tfIdx).getText());
                        }
                        tfIdx++;
                    }
                    case "checkbox" -> {
                        if (cbIdx < checkboxes.size()) {
                            inputs.put(el.key(), String.valueOf(checkboxes.get(cbIdx).isChecked()));
                        }
                        cbIdx++;
                    }
                }
            } else {
                // Advance index even for keyless elements
                switch (el.elementType()) {
                    case "text", "number" -> tfIdx++;
                    case "checkbox"       -> cbIdx++;
                }
            }
        }
        return inputs;
    }

    private PacketByteBuf buildActionPacket(String actionType, String actionValue,
                                             String effectiveStorage,
                                             Map<String, String> inputs) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(screenId);
        buf.writeString(actionType);
        buf.writeString(actionValue);
        buf.writeString(effectiveStorage != null ? effectiveStorage : "");
        buf.writeInt(inputs.size());
        inputs.forEach((k, v) -> {
            buf.writeString(k);
            buf.writeString(v);
        });
        return buf;
    }

    // ── Data records (client-only, deserialized from packet) ─────────────────

    record ElementData(
        String elementType, String key, String label, String tooltip,
        String defaultValue, String min, String max,
        String actionType, String actionValue, String storageTarget
    ) {}

    record ButtonData(
        int slot, String label, String itemId,
        String actionType, String actionValue, String tooltip
    ) {}
}
