package com.legends11.admincore.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Determines whether a raw command line is considered dangerous
 * and provides a human-readable explanation for approval screens.
 */
public class CommandSafetyPolicy {

    // Exact root words (first token, after leading slash stripped)
    private static final Set<String> DANGEROUS_ROOTS = Set.of(
        "stop", "restart", "publish"
    );

    // Regex patterns matched against the normalized full command
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
        Pattern.compile("^op\\b.*",          Pattern.CASE_INSENSITIVE),
        Pattern.compile("^deop\\b.*",        Pattern.CASE_INSENSITIVE),
        Pattern.compile("^ban\\b.*",         Pattern.CASE_INSENSITIVE),
        Pattern.compile("^ban-ip\\b.*",      Pattern.CASE_INSENSITIVE),
        Pattern.compile("^pardon\\b.*",      Pattern.CASE_INSENSITIVE),
        Pattern.compile("^pardon-ip\\b.*",   Pattern.CASE_INSENSITIVE),
        Pattern.compile("^whitelist\\b.*",   Pattern.CASE_INSENSITIVE),
        Pattern.compile("^kick\\b.*",        Pattern.CASE_INSENSITIVE),
        Pattern.compile("^reload\\b.*",      Pattern.CASE_INSENSITIVE),
        Pattern.compile("^save-(all|off|on)\\b.*", Pattern.CASE_INSENSITIVE)
    );

    public record CheckResult(boolean dangerous, String explanation) {}

    /**
     * Returns whether this command requires approval and why.
     * @param rawCommand Raw command string, with or without leading slash.
     */
    public CheckResult check(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return new CheckResult(false, "");
        }

        String normalized = normalize(rawCommand);
        String root = normalized.split("\\s+", 2)[0];

        if (DANGEROUS_ROOTS.contains(root)) {
            return new CheckResult(true, classify(root));
        }

        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(normalized).matches()) {
                return new CheckResult(true, classify(root));
            }
        }

        return new CheckResult(false, "");
    }

    private String normalize(String cmd) {
        return cmd.trim().replaceFirst("^/", "").toLowerCase(Locale.ROOT);
    }

    private String classify(String root) {
        return switch (root) {
            case "op"                       -> "operator privilege escalation";
            case "deop"                     -> "operator privilege removal";
            case "ban", "ban-ip"            -> "player access control";
            case "pardon", "pardon-ip"      -> "player access control";
            case "whitelist"                -> "whitelist modification";
            case "kick"                     -> "player removal";
            case "reload"                   -> "server reload";
            case "save-all", "save-off",
                 "save-on"                  -> "world save control";
            case "stop", "restart"          -> "server shutdown";
            case "publish"                  -> "server exposure";
            default                         -> "policy match";
        };
    }
}
