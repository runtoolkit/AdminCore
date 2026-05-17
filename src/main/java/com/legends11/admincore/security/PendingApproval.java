package com.legends11.admincore.security;

/**
 * Immutable snapshot of a blocked command awaiting approval.
 *
 * @param id          Short UUID fragment used to reference this approval.
 * @param requester   Name of the player/source that issued the command.
 * @param origin      Context label (e.g. "function admincore:…" or "command").
 * @param command     The raw command line that was blocked.
 * @param explanation Human-readable reason it was blocked.
 * @param mode        Whether peer or self approval is required.
 * @param createdAt   System.currentTimeMillis() at creation time.
 */
public record PendingApproval(
    String id,
    String requester,
    String origin,
    String command,
    String explanation,
    ApprovalMode mode,
    long createdAt
) {
    /** Approvals expire after 5 minutes. */
    public static final long TTL_MS = 5 * 60 * 1000L;

    public boolean isExpired() {
        return System.currentTimeMillis() - createdAt > TTL_MS;
    }

    public enum ApprovalMode {
        /** The same player who issued the command can confirm it. */
        SELF,
        /** A different op must confirm — used for highest-risk commands. */
        PEER
    }
}
