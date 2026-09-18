package me.fallenbreath.tcuhc.mixins.block;

import me.fallenbreath.tcuhc.util.SpawnPlatform;
import net.minecraft.block.BlockState;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FarmlandBlock.class)
public abstract class LobbyFarmlandMixin {
	@Inject(method = "setToDirt", at = @At("HEAD"), cancellable = true)
	private static void preserveLobbyFarmland(Entity entity, BlockState state, World world, BlockPos pos, CallbackInfo ci) {
		// Stop trampling/decay at the block conversion, leaving landing damage unchanged.
		if (SpawnPlatform.isProtected(world, pos)) ci.cancel();
	}
}
