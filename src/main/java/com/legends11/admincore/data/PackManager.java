package com.legends11.admincore.data;

import com.google.gson.*;
import com.legends11.admincore.AdminCoreMod;
import com.legends11.admincore.security.CommandApprovalManager;
import com.legends11.admincore.security.CommandSafetyPolicy;
import com.legends11.admincore.security.PendingApproval;
import com.legends11.admincore.net.AdminCoreNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.Optional;
import java.util.zip.ZipFile;

/**
 * Scans the server's datapacks folder and maintains in-memory registries
 * of AdminCore functions, storages, and screens.
 *
 * <h3>Directory layout (inside each datapack)</h3>
 * <pre>
 *   data/&lt;namespace&gt;/admincore/function/&lt;name&gt;.mcfunction
 *   data/&lt;namespace&gt;/admincore/storage/&lt;name&gt;.json
 *   data/&lt;namespace&gt;/admincore/screen/&lt;name&gt;.json
 * </pre>
 *
 * <p>Startup hooks: functions whose path starts with {@code load/}</p>
 * <p>Tick hooks: functions whose path starts with {@code tick/}</p>
 */
public class PackManager {

    // ── Registries ────────────────────────────────────────────────────────────

    private final Map<Identifier, LoadedFunction> functions = new ConcurrentHashMap<>();
    private final Map<Identifier, LoadedStorage>  storages  = new ConcurrentHashMap<>();
    private final Map<Identifier, LoadedScreen>   screens   = new ConcurrentHashMap<>();

    private List<Identifier> startupHooks = List.of();
    private List<Identifier> tickHooks    = List.of();

    private static final Gson GSON = new GsonBuilder().create();

    // Subdirectory names inside each namespace's data folder
    private static final String FUNC_DIR    = "admincore/function";
    private static final String STORAGE_DIR = "admincore/storage";
    private static final String SCREEN_DIR  = "admincore/screen";

    // ── Public exceptions ─────────────────────────────────────────────────────

    public static class DangerousCommandException extends RuntimeException {
        public DangerousCommandException(String msg) { super(msg); }
    }

    // ── Reload ────────────────────────────────────────────────────────────────

    public synchronized void reload(MinecraftServer server) {
        functions.clear();
        storages.clear();
        screens.clear();

        Path datapacksRoot = server.getSavePath(net.minecraft.util.WorldSavePath.DATAPACKS);
        if (!Files.exists(datapacksRoot)) {
            AdminCoreMod.LOGGER.warn("No datapacks folder found at {}", datapacksRoot);
            rebuildHookCaches();
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(datapacksRoot)) {
            for (Path packPath : stream) {
                try {
                    scanPack(packPath);
                } catch (Exception e) {
                    AdminCoreMod.LOGGER.error("Failed scanning pack {}: {}", packPath.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            AdminCoreMod.LOGGER.error("Failed scanning datapacks", e);
        }

        rebuildHookCaches();
    }

    private void rebuildHookCaches() {
        List<Identifier> startup = new ArrayList<>();
        List<Identifier> tick    = new ArrayList<>();
        for (Identifier id : functions.keySet()) {
            String path = id.getPath();
            if (path.startsWith("load/")) startup.add(id);
            else if (path.startsWith("tick/")) tick.add(id);
        }
        startupHooks = List.copyOf(startup);
        tickHooks    = List.copyOf(tick);
    }

    public synchronized void clear() {
        functions.clear();
        storages.clear();
        screens.clear();
        startupHooks = List.of();
        tickHooks    = List.of();
    }

    // ── Pack scanning ─────────────────────────────────────────────────────────

    private void scanPack(Path packRoot) throws IOException {
        if (!Files.isDirectory(packRoot)) {
            if (packRoot.toString().endsWith(".zip")) {
                scanZipPack(packRoot);
            }
            return;
        }

        // Validate pack.mcmeta (warn only, don't skip)
        Path meta = packRoot.resolve("pack.mcmeta");
        if (!Files.exists(meta)) return;
        validatePackMeta(packRoot, meta);

        Path dataRoot = packRoot.resolve("data");
        if (!Files.isDirectory(dataRoot)) return;

        try (DirectoryStream<Path> ns = Files.newDirectoryStream(dataRoot)) {
            for (Path namespaceDir : ns) {
                if (Files.isDirectory(namespaceDir)) {
                    String namespace = namespaceDir.getFileName().toString();
                    scanFilesystemNamespace(namespace, namespaceDir);
                }
            }
        }
    }

    private void scanFilesystemNamespace(String namespace, Path namespaceDir) {
        scanFilesystemFunctions(namespace, namespaceDir.resolve(FUNC_DIR));
        scanFilesystemStorages (namespace, namespaceDir.resolve(STORAGE_DIR));
        scanFilesystemScreens  (namespace, namespaceDir.resolve(SCREEN_DIR));
    }

    private void scanFilesystemFunctions(String namespace, Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.toString().endsWith(".mcfunction") && Files.isRegularFile(p))
                 .forEach(file -> {
                     try {
                         Identifier id = pathToId(namespace, dir, file, ".mcfunction");
                         List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8)
                             .stream()
                             .map(String::trim)
                             .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                             .toList();
                         Identifier storageId = resolveDefaultStorage(namespace, id);
                         functions.put(id, new LoadedFunction(id, lines, storageId, file));
                     } catch (Exception e) {
                         AdminCoreMod.LOGGER.error("Failed loading function {}: {}", file, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            AdminCoreMod.LOGGER.error("Failed walking function dir {}", dir, e);
        }
    }

    private void scanFilesystemStorages(String namespace, Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.toString().endsWith(".json") && Files.isRegularFile(p))
                 .forEach(file -> {
                     try {
                         Identifier id = pathToId(namespace, dir, file, ".json");
                         JsonObject obj = GSON.fromJson(
                             Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                         Map<String, String> values = new LinkedHashMap<>();
                         flattenJson("", obj, values);
                         storages.put(id, new LoadedStorage(id, values, file));
                     } catch (Exception e) {
                         AdminCoreMod.LOGGER.error("Failed loading storage {}: {}", file, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            AdminCoreMod.LOGGER.error("Failed walking storage dir {}", dir, e);
        }
    }

    private void scanFilesystemScreens(String namespace, Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.toString().endsWith(".json") && Files.isRegularFile(p))
                 .forEach(file -> {
                     try {
                         Identifier id = pathToId(namespace, dir, file, ".json");
                         JsonObject obj = GSON.fromJson(
                             Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                         LoadedScreen screen = parseScreen(id, obj, file);
                         screens.put(id, screen);
                     } catch (Exception e) {
                         AdminCoreMod.LOGGER.error("Failed loading screen {}: {}", file, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            AdminCoreMod.LOGGER.error("Failed walking screen dir {}", dir, e);
        }
    }

    private void scanZipPack(Path zipPath) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            // Collect namespaces
            Set<String> namespaces = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                String[] parts = name.split("/");
                if (parts.length >= 3 && "data".equals(parts[0])) {
                    namespaces.add(parts[1]);
                }
            }
            for (String ns : namespaces) {
                scanZipNamespace(zip, ns);
            }
        } catch (IOException e) {
            AdminCoreMod.LOGGER.error("Failed scanning zip pack {}: {}", zipPath.getFileName(), e.getMessage());
        }
    }

    private void scanZipNamespace(ZipFile zip, String namespace) {
        String funcPrefix    = "data/" + namespace + "/" + FUNC_DIR + "/";
        String storagePrefix = "data/" + namespace + "/" + STORAGE_DIR + "/";
        String screenPrefix  = "data/" + namespace + "/" + SCREEN_DIR + "/";

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();

            try {
                if (name.startsWith(funcPrefix) && name.endsWith(".mcfunction")) {
                    String rel = name.substring(funcPrefix.length(), name.length() - ".mcfunction".length());
                    Identifier id = Identifier.of(namespace, rel);
                    List<String> lines = readZipLines(zip, entry);
                    Identifier storageId = resolveDefaultStorage(namespace, id);
                    functions.put(id, new LoadedFunction(id, lines, storageId, null));

                } else if (name.startsWith(storagePrefix) && name.endsWith(".json")) {
                    String rel = name.substring(storagePrefix.length(), name.length() - ".json".length());
                    Identifier id = Identifier.of(namespace, rel);
                    JsonObject obj = GSON.fromJson(readZipText(zip, entry), JsonObject.class);
                    Map<String, String> values = new LinkedHashMap<>();
                    flattenJson("", obj, values);
                    storages.put(id, new LoadedStorage(id, values, null));

                } else if (name.startsWith(screenPrefix) && name.endsWith(".json")) {
                    String rel = name.substring(screenPrefix.length(), name.length() - ".json".length());
                    Identifier id = Identifier.of(namespace, rel);
                    JsonObject obj = GSON.fromJson(readZipText(zip, entry), JsonObject.class);
                    screens.put(id, parseScreen(id, obj, null));
                }
            } catch (Exception e) {
                AdminCoreMod.LOGGER.error("Failed loading zip entry {}: {}", name, e.getMessage());
            }
        }
    }

    // ── Screen parsing ────────────────────────────────────────────────────────

    private LoadedScreen parseScreen(Identifier id, JsonObject obj, Path sourcePath) {
        String title = obj.has("title") ? obj.get("title").getAsString() : id.toString();
        int rows = obj.has("rows") ? Math.min(6, Math.max(1, obj.get("rows").getAsInt())) : 3;

        Identifier storageTarget = null;
        if (obj.has("storage_target") && !obj.get("storage_target").isJsonNull()) {
            String raw = obj.get("storage_target").getAsString();
            if (!raw.isBlank()) {
                storageTarget = Identifier.of(raw.contains(":") ? raw.split(":")[0] : "minecraft",
                                               raw.contains(":") ? raw.split(":", 2)[1] : raw);
            }
        }

        List<LoadedScreenElement> elements = new ArrayList<>();
        if (obj.has("elements") && obj.get("elements").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("elements");
            for (int i = 0; i < arr.size(); i++) {
                if (!arr.get(i).isJsonObject()) continue;
                JsonObject el = arr.get(i).getAsJsonObject();
                LoadedScreenElement parsed = parseElement(el, i, storageTarget);
                if (parsed != null) elements.add(parsed);
            }
        }

        List<LoadedScreenButton> buttons = new ArrayList<>();
        if (obj.has("buttons") && obj.get("buttons").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("buttons");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                buttons.add(parseButton(el.getAsJsonObject()));
            }
        }

        return new LoadedScreen(id, title, rows, List.copyOf(elements),
                                List.copyOf(buttons), storageTarget, sourcePath);
    }

    private LoadedScreenElement parseElement(JsonObject el, int index, Identifier screenStorage) {
        String type = getString(el, "type", "label");

        // Require "key" for all interactive elements
        String key = getString(el, "key", null);
        if (key == null && !"label".equals(type)) {
            AdminCoreMod.LOGGER.warn(
                "Screen element [{}] of type '{}' is missing required 'key' field — skipped.", index, type);
            return null;
        }
        if (key == null) key = "";  // labels don't need a key

        String label        = getString(el, "label", "");
        String tooltip      = getString(el, "tooltip", "");
        String defaultValue = getString(el, "default", "");
        String min          = getString(el, "min", null);
        String max          = getString(el, "max", null);
        String actionType   = getString(el, "action_type", null);
        String actionValue  = getString(el, "action_value", null);

        // Element-level storage override
        Identifier elemStorage = null;
        if (el.has("storage_target") && !el.get("storage_target").isJsonNull()) {
            String raw = el.get("storage_target").getAsString();
            if (!raw.isBlank()) elemStorage = parseId(raw);
        }

        return new LoadedScreenElement(type, key, label, tooltip, defaultValue,
                                       min, max, actionType, actionValue, elemStorage);
    }

    private LoadedScreenButton parseButton(JsonObject obj) {
        int slot          = obj.has("slot") ? obj.get("slot").getAsInt() : 0;
        String label      = getString(obj, "label", "Button");
        String itemId     = getString(obj, "item", "minecraft:paper");
        String actionType = getString(obj, "action_type", "");
        String actionValue= getString(obj, "action_value", "");
        String tooltip    = getString(obj, "tooltip", "");
        return new LoadedScreenButton(slot, label, itemId, actionType, actionValue, tooltip);
    }

    // ── Function execution ────────────────────────────────────────────────────

    /**
     * Executes a function by id.
     *
     * <p>Storage resolution order:</p>
     * <ol>
     *   <li>If {@code overrideStorage} is non-null, use it.</li>
     *   <li>Otherwise, use the function's own {@code storageId}.</li>
     * </ol>
     *
     * @throws DangerousCommandException if a line is blocked and approval is created.
     */
    public void executeFunction(Identifier id, ServerCommandSource source,
                                CommandSafetyPolicy policy, CommandApprovalManager approvals)
            throws DangerousCommandException {
        executeFunction(id, source, policy, approvals, null);
    }

    public void executeFunction(Identifier id, ServerCommandSource source,
                                CommandSafetyPolicy policy, CommandApprovalManager approvals,
                                Identifier overrideStorageId)
            throws DangerousCommandException {

        LoadedFunction fn = functions.get(id);
        if (fn == null) {
            AdminCoreMod.LOGGER.warn("Function not found: {}", id);
            return;
        }

        // Resolve storage
        Identifier resolvedStorageId = overrideStorageId != null ? overrideStorageId : fn.storageId();
        Map<String, String> vars = resolvedStorageId != null
            ? Optional.ofNullable(storages.get(resolvedStorageId))
                      .map(LoadedStorage::snapshot)
                      .orElse(Map.of())
            : Map.of();

        AdminCoreMod.LOGGER.info("Running function {}...", id);

        for (String rawLine : fn.lines()) {
            String expanded = TemplateExpander.expand(rawLine, source, vars);
            if (expanded.isBlank()) continue;

            CommandSafetyPolicy.CheckResult check = policy.check(expanded);
            if (check.dangerous() && !approvals.isBypassActive()) {
                // Check if pre-approved
                if (approvals.consumeApproval(expanded)) {
                    runLine(expanded, source, approvals);
                    continue;
                }

                // Block and create approval
                String requesterName = source != null ? source.getName() : "server";
                PendingApproval.ApprovalMode mode = PendingApproval.ApprovalMode.PEER;
                PendingApproval approval = approvals.create(
                    requesterName,
                    "function " + id,
                    expanded,
                    check.explanation(),
                    mode);

                AdminCoreMod.LOGGER.warn("Blocked function command '{}' from {} ({}). approval={}",
                    expanded, requesterName, check.explanation(), approval.id());

                // Notify issuing player if available
                ServerPlayerEntity player = source != null ? source.getPlayer() : null;
                if (player != null) {
                    com.legends11.admincore.net.AdminCoreNetwork.sendApprovalScreen(player, approval);
                }

                throw new DangerousCommandException(
                    "Blocked: " + expanded + " (" + check.explanation() + "). approval=" + approval.id());
            }

            runLine(expanded, source, approvals);
        }
    }

    private void runLine(String command, ServerCommandSource source, CommandApprovalManager approvals) {
        MinecraftServer server = AdminCoreMod.server();
        if (server == null) return;
        try {
            approvals.runBypassed(() -> {
                server.getCommandManager().executeWithPrefix(source, command);
                return null;
            });
        } catch (Exception e) {
            AdminCoreMod.LOGGER.error("Error running line '{}': {}", command, e.getMessage());
        }
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    public void runStartupHooks(MinecraftServer server, CommandSafetyPolicy policy,
                                CommandApprovalManager approvals) {
        ServerCommandSource source = server.getCommandSource();
        for (Identifier id : startupHooks) {
            try {
                executeFunction(id, source, policy, approvals);
            } catch (DangerousCommandException e) {
                AdminCoreMod.LOGGER.warn("Blocked startup function {} ({})", id, e.getMessage());
            }
        }
    }

    public void runTickHooks(MinecraftServer server, CommandSafetyPolicy policy,
                             CommandApprovalManager approvals) {
        ServerCommandSource source = server.getCommandSource();
        for (Identifier id : tickHooks) {
            try {
                executeFunction(id, source, policy, approvals);
            } catch (DangerousCommandException e) {
                // Tick hooks should not spam approval screens; just log at debug
                AdminCoreMod.LOGGER.debug("Tick hook {} blocked: {}", id, e.getMessage());
            }
        }
    }

    // ── Storage write-back (from screen submissions) ──────────────────────────

    /**
     * Merges player-submitted key-value pairs into a storage.
     * If the storage doesn't exist yet in-memory, it is created on the fly.
     *
     * @param storageId Target storage identifier.
     * @param inputs    Key-value pairs from the screen submission.
     */
    public void writeToStorage(Identifier storageId, Map<String, String> inputs) {
        if (storageId == null || inputs == null || inputs.isEmpty()) return;
        storages.compute(storageId, (k, existing) -> {
            if (existing == null) {
                Map<String, String> values = new LinkedHashMap<>(inputs);
                return new LoadedStorage(storageId, values, null);
            }
            existing.values().putAll(inputs);
            return existing;
        });
        AdminCoreMod.LOGGER.debug("Wrote {} input(s) to storage {}", inputs.size(), storageId);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public Optional<LoadedFunction> function(Identifier id) { return Optional.ofNullable(functions.get(id)); }
    public Optional<LoadedStorage>  storage(Identifier id)  { return Optional.ofNullable(storages.get(id));  }
    public Optional<LoadedScreen>   screen(Identifier id)   { return Optional.ofNullable(screens.get(id));   }

    public Set<Identifier> functionIds() { return Collections.unmodifiableSet(functions.keySet()); }
    public Set<Identifier> storageIds()  { return Collections.unmodifiableSet(storages.keySet());  }
    public Set<Identifier> screenIds()   { return Collections.unmodifiableSet(screens.keySet());   }

    public int functionCount() { return functions.size(); }
    public int storageCount()  { return storages.size();  }
    public int screenCount()   { return screens.size();   }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Identifier pathToId(String namespace, Path base, Path file, String suffix) {
        String rel = base.relativize(file).toString()
            .replace(File.separatorChar, '/')
            .replace('\\', '/');
        if (rel.endsWith(suffix)) rel = rel.substring(0, rel.length() - suffix.length());
        return Identifier.of(namespace, rel);
    }

    private List<String> readZipLines(ZipFile zip, ZipEntry entry) throws IOException {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            return r.lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
        }
    }

    private String readZipText(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream is = zip.getInputStream(entry)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Flatten a JSON object into dot-notation key-value pairs. */
    private void flattenJson(String prefix, JsonObject obj, Map<String, String> out) {
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement val = entry.getValue();
            if (val.isJsonObject()) {
                flattenJson(key, val.getAsJsonObject(), out);
            } else if (!val.isJsonNull()) {
                out.put(key, val.getAsString());
            }
        }
    }

    private void validatePackMeta(Path packRoot, Path metaPath) {
        try {
            JsonObject meta = GSON.fromJson(Files.readString(metaPath), JsonObject.class);
            int fmt = meta.getAsJsonObject("pack").get("pack_format").getAsInt();
            if (fmt != 26) {  // 1.20.4 pack format
                AdminCoreMod.LOGGER.warn("Pack {} has pack_format {} (expected 26 for 1.20.4)",
                    packRoot.getFileName(), fmt);
            }
        } catch (Exception e) {
            AdminCoreMod.LOGGER.warn("Unable to read pack.mcmeta for {}: {}", packRoot.getFileName(), e.getMessage());
        }
    }

    /**
     * Tries to find a same-namespace storage with the same path as the function.
     * e.g. function "mynamespace:setup/init" → storage "mynamespace:setup/init" (if it exists).
     * This is purely a convention; returns null if not found.
     */
    private Identifier resolveDefaultStorage(String namespace, Identifier functionId) {
        Identifier candidate = Identifier.of(namespace, functionId.getPath());
        return storages.containsKey(candidate) ? candidate : null;
    }

    private Identifier parseId(String raw) {
        if (raw.contains(":")) {
            String[] parts = raw.split(":", 2);
            return Identifier.of(parts[0], parts[1]);
        }
        return Identifier.of("minecraft", raw);
    }

    private String getString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        return obj.get(key).getAsString();
    }
}
