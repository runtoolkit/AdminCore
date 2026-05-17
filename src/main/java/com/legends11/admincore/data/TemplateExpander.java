package com.legends11.admincore.data;

import net.minecraft.server.command.ServerCommandSource;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {{key}} templates in mcfunction lines.
 *
 * <p>Supported token forms inside {{ }}:</p>
 * <pre>
 *   {{player}}              → name of the executing player (or source name)
 *   {{player.uuid}}         → UUID of the executing player
 *   {{key}}                 → looks up "key" in the provided storage map
 *   {{storage.key}}         → same — explicit "storage." prefix also accepted
 *   {{literal.some text}}   → the string after "literal." verbatim
 * </pre>
 *
 * <p>Unknown keys resolve to an empty string. A line with no {{ }} is
 * returned unchanged with zero regex overhead.</p>
 */
public class TemplateExpander {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{([^}]+)}}");

    private TemplateExpander() {}

    /**
     * Expands all {{…}} tokens in {@code input}.
     *
     * @param input    The raw mcfunction line.
     * @param source   The command source executing the function (may be null for tick hooks).
     * @param storage  Key-value pairs from the resolved storage, or an empty map.
     * @return Expanded line, or the original if no tokens were present.
     */
    public static String expand(String input, ServerCommandSource source, Map<String, String> storage) {
        if (input == null || !input.contains("{{")) return input;

        Matcher m = TOKEN.matcher(input);
        if (!m.find()) return input;

        // Reset and do full replacement
        m.reset();
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String token = m.group(1).trim();
            String replacement = resolve(token, source, storage);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String resolve(String token, ServerCommandSource source, Map<String, String> storage) {
        // {{player}} → player name
        if (token.equalsIgnoreCase("player")) {
            return source != null ? source.getName() : "";
        }

        // {{player.uuid}} → player UUID
        if (token.equalsIgnoreCase("player.uuid")) {
            if (source != null && source.getPlayer() != null) {
                return source.getPlayer().getUuidAsString();
            }
            return "";
        }

        // {{literal.some text}} → "some text"
        if (token.startsWith("literal.")) {
            return token.substring("literal.".length());
        }

        // {{storage.key}} → storage lookup with explicit prefix
        if (token.startsWith("storage.")) {
            String key = token.substring("storage.".length());
            return storage.getOrDefault(key, "");
        }

        // {{key}} → direct storage lookup (most common case)
        return storage.getOrDefault(token, "");
    }
}
