package com.legends11.admincore;

import com.legends11.admincore.data.PackManager;
import com.legends11.admincore.item.AdminCoreItems;
import com.legends11.admincore.net.AdminCoreNetwork;
import com.legends11.admincore.security.CommandSafetyPolicy;
import com.legends11.admincore.security.CommandApprovalManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AdminCoreMod implements ModInitializer {

    public static final String MOD_ID = "admincore";
    public static final String VERSION = "2.0.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Singletons — server-scoped, reset on stop
    private static PackManager packManager;
    private static CommandSafetyPolicy safetyPolicy;
    private static CommandApprovalManager approvalManager;
    private static MinecraftServer activeServer;

    // Evict expired approvals every 20s (400 ticks)
    private static final int EVICT_INTERVAL = 400;
    private static int evictTicker = 0;

    @Override
    public void onInitialize() {
        LOGGER.info("AdminCore {} starting", VERSION);

        safetyPolicy = new CommandSafetyPolicy();
        approvalManager = new CommandApprovalManager();
        packManager = new PackManager();

        AdminCoreItems.register();
        AdminCoreNetwork.registerServer();
        AdminCoreCommands.register(safetyPolicy, approvalManager, packManager);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            activeServer = server;
            packManager.reload(server);
            packManager.runStartupHooks(server, safetyPolicy, approvalManager);
            LOGGER.info("AdminCore ready. Functions={}, Storages={}, Screens={}",
                    packManager.functionCount(), packManager.storageCount(), packManager.screenCount());
        });

        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                packManager.reload(server);
                LOGGER.info("AdminCore reloaded after datapack reload.");
            }
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            approvalManager.clearAll();
            packManager.clear();
            activeServer = null;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++evictTicker >= EVICT_INTERVAL) {
                evictTicker = 0;
                approvalManager.evictExpired();
            }
            packManager.runTickHooks(server, safetyPolicy, approvalManager);
        });
    }

    public static PackManager packs() { return packManager; }
    public static CommandSafetyPolicy policy() { return safetyPolicy; }
    public static CommandApprovalManager approvals() { return approvalManager; }
    public static MinecraftServer server() { return activeServer; }
}
