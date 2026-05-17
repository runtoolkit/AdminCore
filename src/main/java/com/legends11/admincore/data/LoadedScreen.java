package com.legends11.admincore.data;

import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.List;

/**
 * A datapack-defined GUI screen loaded from
 * {@code data/<ns>/admincore/screen/<name>.json}.
 *
 * @param storageTarget Screen-level default storage for element write-back.
 *                      If null, the invoked function's own storage is used.
 *                      Element-level {@code storage_target} overrides this per-element.
 */
public record LoadedScreen(
    Identifier id,
    String title,
    int rows,
    List<LoadedScreenElement> elements,
    List<LoadedScreenButton> buttons,
    Identifier storageTarget,
    Path sourcePath
) {}
