package me.fallenbreath.tcuhc.mixins.block;

import me.fallenbreath.tcuhc.util.SpawnPlatform;
import net.minecraft.block.AbstractBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class LobbyBlockTicksMixin {
	@Inject(method = {"randomTick", "scheduledTick"}, at = @At("HEAD"), cancellable = true)
	private void preserveLobbyBlocks(ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
		// Freeze only the active lobby: ice, snow, leaves, decorative fire and water stay intact.
		if (SpawnPlatform.isProtected(world, pos)) ci.cancel();
	}
}
