package com.legends11.admincore.data;

/**
 * A legacy inventory-slot-style button for a {@link LoadedScreen}.
 *
 * <p>These map to visual slots in the GUI (not a real inventory).
 * Clicking sends {@code actionType}/{@code actionValue} immediately
 * without collecting form values.</p>
 */
public record LoadedScreenButton(
    int slot,
    String label,
    String itemId,
    String actionType,
    String actionValue,
    String tooltip
) {}
