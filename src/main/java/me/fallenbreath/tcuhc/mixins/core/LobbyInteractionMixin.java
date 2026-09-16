package me.fallenbreath.tcuhc.mixins.core;

import me.fallenbreath.tcuhc.util.SpawnPlatform;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerInteractionManager.class)
public abstract class LobbyInteractionMixin {
	@Shadow protected ServerWorld world;
	@Shadow @Final protected ServerPlayerEntity player;

	@Inject(method = "tryBreakBlock", at = @At("HEAD"), cancellable = true)
	private void protectLobbyBlocks(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (!player.isCreative() && SpawnPlatform.isProtected(world, pos)) cir.setReturnValue(false);
	}

	@Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
	private void protectLobbyInteractions(ServerPlayerEntity player, World world, ItemStack stack, Hand hand,
			BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
		if (!player.isCreative() && (SpawnPlatform.isProtected(world, hit.getBlockPos())
				|| SpawnPlatform.isProtected(world, hit.getBlockPos().offset(hit.getSide())))) {
			cir.setReturnValue(ActionResult.FAIL);
		}
	}
}
