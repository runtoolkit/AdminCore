package com.legends11.admincore.item;

import com.legends11.admincore.AdminCoreMod;
import com.legends11.admincore.net.AdminCoreNetwork;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

public class AdminCoreWandItem extends Item {

    /** The screen that the wand opens. Override by adding this to your datapack. */
    private static final Identifier CONTROL_SCREEN =
            new Identifier(AdminCoreMod.MOD_ID, "control");

    public AdminCoreWandItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);

        if (world.isClient) {
            return TypedActionResult.success(stack);
        }

        ServerPlayerEntity player = (ServerPlayerEntity) user;

        // Op check (permission level 2)
        if (!player.hasPermissionLevel(2)) {
            player.sendMessage(
                Text.literal("You do not have permission to use the AdminCore Wand."),
                true);
            AdminCoreMod.LOGGER.warn("{} tried to use AdminCore Wand without op permission.",
                    player.getName().getString());
            return TypedActionResult.fail(stack);
        }

        var packs = AdminCoreMod.packs();
        var screen = packs.screen(CONTROL_SCREEN);

        if (screen.isEmpty()) {
            player.sendMessage(
                Text.literal("AdminCore: no screen defined at admincore:control. "
                           + "Create data/<namespace>/admincore/screen/control.json in your datapack."),
                false);
            return TypedActionResult.fail(stack);
        }

        AdminCoreNetwork.sendScreen(player, screen.get());
        return TypedActionResult.success(stack);
    }
}
