package net.mll.nbtcrashfix.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class ItemFrameColorMixin {
	@Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
	private void NCF$color(CallbackInfoReturnable<Integer> cir) {
		if ((Object)this instanceof ItemFrame) {
			cir.setReturnValue(0xFF3030);
		}
	}
}
