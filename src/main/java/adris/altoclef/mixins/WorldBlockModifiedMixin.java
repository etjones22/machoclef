package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockPlaceEvent;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(World.class)
public class WorldBlockModifiedMixin {

    private static boolean hasBlock(BlockState state, BlockPos pos) {
        return !state.isAir() && state.isSolidBlock(MinecraftClient.getInstance().world, pos);
    }

    @Inject(
            method = "onBlockStateChanged(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/BlockState;)V",
            at = @At("HEAD")
    )
    public void onBlockStateChanged(BlockPos pos, BlockState oldBlock, BlockState newBlock, CallbackInfo ci) {
        if (!hasBlock(oldBlock, pos) && hasBlock(newBlock, pos)) {
            BlockPlaceEvent evt = new BlockPlaceEvent(pos, newBlock);
            EventBus.publish(evt);
        }
    }
}
