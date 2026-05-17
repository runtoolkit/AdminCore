package com.legends11.admincore.data;

import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * A flat key-value store loaded from {@code data/<ns>/admincore/storage/<name>.json}.
 *
 * <p>Nested JSON objects are flattened with dot notation at load time:
 * {@code {"a":{"b":"v"}}} becomes {@code "a.b" -> "v"}.
 * All values are strings.</p>
 *
 * <p>{@code values} is mutable so screen submissions can update it in-place.</p>
 */
public record LoadedStorage(
    Identifier id,
    Map<String, String> values,
    Path sourcePath
) {
    /**
     * Returns a mutable copy of the current values, safe to pass to
     * {@link TemplateExpander} without risk of concurrent modification.
     */
    public Map<String, String> snapshot() {
        return new HashMap<>(values);
    }
}
