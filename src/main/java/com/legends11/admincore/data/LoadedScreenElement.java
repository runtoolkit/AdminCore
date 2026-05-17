package com.legends11.admincore.data;

import net.minecraft.util.Identifier;

/**
 * One interactive element inside a {@link LoadedScreen}.
 *
 * <h3>Element types</h3>
 * <ul>
 *   <li>{@code text}     — single-line text field</li>
 *   <li>{@code number}   — integer field (optional min/max)</li>
 *   <li>{@code checkbox} — boolean toggle</li>
 *   <li>{@code label}    — read-only text; {@code key} is not required</li>
 *   <li>{@code button}   — triggers {@code actionType}/{@code actionValue} immediately</li>
 * </ul>
 *
 * <h3>Key requirement</h3>
 * The parser enforces that {@code key} is non-null for all types except {@code label}.
 * Elements missing a required {@code key} are skipped with a warning.
 *
 * <h3>Storage resolution</h3>
 * When the form is submitted, this element's value is written to:
 * <ol>
 *   <li>{@code storageTarget} if non-null (element-level override)</li>
 *   <li>The parent screen's {@code storage_target} otherwise</li>
 *   <li>The invoked function's default storage as last resort</li>
 * </ol>
 */
public record LoadedScreenElement(
    String elementType,
    String key,
    String label,
    String tooltip,
    String defaultValue,
    String min,
    String max,
    String actionType,
    String actionValue,
    Identifier storageTarget
) {
    public boolean isText()     { return "text".equals(elementType); }
    public boolean isCheckbox() { return "checkbox".equals(elementType); }
    public boolean isNumber()   { return "number".equals(elementType); }
    public boolean isLabel()    { return "label".equals(elementType); }
    public boolean isButton()   { return "button".equals(elementType); }
}
