package com.legends11.admincore.data;

import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.List;

/**
 * A loaded .mcfunction file.
 *
 * @param id         Namespace:path identifier (e.g. {@code mynamespace:setup/init}).
 * @param lines      Raw (unexpanded) command lines; blank lines and comments stripped.
 * @param storageId  Default storage injected at execution time. Null if none declared.
 * @param sourcePath Filesystem path for error reporting. Null for ZIP-sourced functions.
 */
public record LoadedFunction(
    Identifier id,
    List<String> lines,
    Identifier storageId,
    Path sourcePath
) {}
