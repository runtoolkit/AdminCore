package com.legends11.admincore.security;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store for pending and approved commands.
 *
 * <p>Peer-approval commands use a separate approved set so the approving
 * player's confirm action unlocks execution on the next attempt. Self-approval
 * commands are approved inline when the same player confirms.</p>
 */
public class CommandApprovalManager {

    private final Object pendingLock = new Object();
    private final Map<String, PendingApproval> pendingById = new ConcurrentHashMap<>();

    // Approved (confirmed) normalized command keys — single-use
    private final Object approvedLock = new Object();
    private final Set<String> approvedCommands = ConcurrentHashMap.newKeySet();

    // Thread-local bypass flag for the mixin so approved execution doesn't loop
    static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    /** Creates and stores a new pending approval. Returns the id. */
    public PendingApproval create(String requester, String origin,
                                   String command, String explanation,
                                   PendingApproval.ApprovalMode mode) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String normalizedCmd = normalize(command);

        // Deduplicate: if same command already pending, return existing
        synchronized (pendingLock) {
            for (PendingApproval existing : pendingById.values()) {
                if (existing.command().equalsIgnoreCase(normalizedCmd) && !existing.isExpired()) {
                    return existing;
                }
            }
            PendingApproval approval = new PendingApproval(
                id, requester, origin, command, explanation, mode,
                System.currentTimeMillis());
            pendingById.put(id, approval);
            return approval;
        }
    }

    public Optional<PendingApproval> get(String id) {
        return Optional.ofNullable(pendingById.get(id));
    }

    public void remove(String id) {
        pendingById.remove(id);
    }

    /** Mark an approval as granted; the next execution attempt will bypass the block. */
    public void approve(String id) {
        PendingApproval a = pendingById.remove(id);
        if (a != null) {
            synchronized (approvedLock) {
                approvedCommands.add(normalize(a.command()));
            }
        }
    }

    /** True if this exact command has been pre-approved (consumes the approval). */
    public boolean consumeApproval(String command) {
        String key = normalize(command);
        synchronized (approvedLock) {
            return approvedCommands.remove(key);
        }
    }

    public boolean isBypassActive() {
        return Boolean.TRUE.equals(BYPASS.get());
    }

    public <T> T runBypassed(Callable<T> action) throws Exception {
        Boolean previous = BYPASS.get();
        BYPASS.set(true);
        try {
            return action.call();
        } finally {
            BYPASS.set(previous);
        }
    }

    public Set<String> pendingIds() {
        return Set.copyOf(pendingById.keySet());
    }

    public int pendingCount() {
        return pendingById.size();
    }

    public void clearAll() {
        pendingById.clear();
        approvedCommands.clear();
    }

    public void evictExpired() {
        pendingById.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private String normalize(String cmd) {
        if (cmd == null) return "";
        return cmd.trim().replaceFirst("^/", "").toLowerCase(Locale.ROOT);
    }
}
