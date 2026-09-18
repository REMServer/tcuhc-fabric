package me.fallenbreath.tcuhc.mixins.block;

import me.fallenbreath.tcuhc.util.SpawnPlatform;
import net.minecraft.block.BlockState;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetherPortalBlock.class)
public abstract class LobbyNetherPortalMixin {
	@Inject(method = "onEntityCollision", at = @At("HEAD"), cancellable = true)
	private void keepLobbyPortalsDecorative(BlockState state, World world, BlockPos pos, Entity entity, CallbackInfo ci) {
		// Cancel portal entry, not rendering: the original blocks and particles remain.
		// Applies to all entities and game modes, only inside the active Overworld lobby.
		if (SpawnPlatform.isProtected(world, pos)) ci.cancel();
	}
}
