package com.legends11.admincore.item;

import com.legends11.admincore.AdminCoreMod;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class AdminCoreItems {

    public static final Item WAND = new AdminCoreWandItem(
            new FabricItemSettings().maxCount(1));

    public static void register() {
        Registry.register(
                Registries.ITEM,
                new Identifier(AdminCoreMod.MOD_ID, "wand"),
                WAND);

        // Add to the Tools & Utilities creative tab
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries ->
                entries.add(WAND));
    }
}
