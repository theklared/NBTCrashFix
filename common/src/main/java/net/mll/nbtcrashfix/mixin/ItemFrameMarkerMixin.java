package net.mll.nbtcrashfix.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class ItemFrameMarkerMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void NCF$markers(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null | mc.options.hideGui)
			return;

		Camera cam = mc.gameRenderer.getMainCamera();
		Vec3 camPos = cam.position();
		Vector3fc forward = cam.forwardVector();
		Vector3fc up = cam.upVector();
		Vector3fc left = cam.leftVector();

		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		double fov = Math.toRadians(mc.options.fov().get());
		double focal = (h / 2.0) / Math.tan(fov / 2.0);

		// poorly made but thats okay
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof ItemFrame)) continue;

			double dx = e.getX() - camPos.x;
			double dy = e.getY() - camPos.y;
			double dz = e.getZ() - camPos.z;

			double depth = dx * forward.x() + dy * forward.y() + dz * forward.z();
			if (depth <= 0.1)
				continue;

			double sx = w / 2.0 - (dx * left.x() + dy * left.y() + dz * left.z()) / depth * focal;
			double sy = h / 2.0 - (dx * up.x() + dy * up.y() + dz * up.z()) / depth * focal;
			if (sx < 0 || sx > w || sy < 0 || sy > h)
				continue;

			int x = (int) sx;
			int y = (int) sy;
			g.fill(x - 3, y - 3, x + 3, y + 3, 0xFFFF3030);

			String dist = (int) Math.sqrt(dx * dx + dy * dy + dz * dz) + "m";
			g.drawString(mc.font, dist, x + 6, y - 4, 0xFFFF3030, true);
		}
	}
}
