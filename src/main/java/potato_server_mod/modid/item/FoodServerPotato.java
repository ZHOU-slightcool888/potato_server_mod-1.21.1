package potato_server_mod.modid.item;

import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import potato_server_mod.modid.show.PotatoServerShow;

import java.util.List;

public class FoodServerPotato extends Item {
    public FoodServerPotato() {
        super(new Settings().maxCount(1).food(new FoodComponent.Builder()
                .nutrition(2).saturationModifier(0.1F).alwaysEdible().build()));
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        if (user instanceof ServerPlayerEntity player && !PotatoServerShow.canEat(player)) {
            return TypedActionResult.fail(user.getStackInHand(hand));
        }
        return super.use(world, user, hand);
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        // Check again before consuming: starting to eat must not bypass the cooldown.
        if (user instanceof ServerPlayerEntity player && !PotatoServerShow.start(player)) {
            return stack;
        }
        return super.finishUsing(stack, world, user);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.potato_server_mod.server_potato.tooltip"));
    }
}
