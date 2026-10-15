package dev.strafemc.client.weapon;

import dev.strafemc.weapon.GunItem;
import dev.strafemc.weapon.WeaponStats;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** CS-style ammo readout (bottom right) while holding a CS gun: rounds in the magazine | magazines left, with CS2's HUD icons. */
public final class AmmoHud {
	private static final Identifier FULLAUTO = Identifier.fromNamespaceAndPath("strafemc", "textures/gui/hud/ammo_fullauto.png");
	private static final Identifier BURST = Identifier.fromNamespaceAndPath("strafemc", "textures/gui/hud/ammo_burst.png");
	private static final Identifier BULLET = Identifier.fromNamespaceAndPath("strafemc", "textures/gui/hud/ammo_bullet.png");
	private static final Identifier MAGAZINE = Identifier.fromNamespaceAndPath("strafemc", "textures/gui/hud/ammo_reserve_magazine.png");

	private AmmoHud() {}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Identifier.fromNamespaceAndPath("strafemc", "ammo"), (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.options.hideGui || !(mc.player.getMainHandItem().getItem() instanceof GunItem gun)
					|| gun instanceof dev.strafemc.weapon.KnifeItem || gun instanceof dev.strafemc.weapon.GrenadeItem
					|| gun instanceof dev.strafemc.weapon.HealthshotItem || gun.weaponClass.equals(dev.strafemc.weapon.ZeusRecharge.CLS)) return; // the Zeus shows its charge on the gun
			WeaponStats w = dev.strafemc.client.ClientGameplay.stats(gun.weaponClass);
			if (w == null) return;
			int clip = gun.clip(mc.player.getMainHandItem());
			int mags = GunClient.magazineCount(mc.player, gun);
			int inf = GunClient.infiniteAmmo();
			int sw = g.guiWidth(), sh = g.guiHeight();
			var font = mc.font;
			String big = String.valueOf(clip);
			String small = inf == 2 ? "∞" : String.valueOf(mags);
			int lowColor = clip <= Math.max(1, w.clip() / 5) ? 0xFFFF5050 : 0xFFFFFFFF;
			int x = sw - 12, y = sh - 24;

			// reserve: "| N" + magazine icon, right-aligned
			g.blit(RenderPipelines.GUI_TEXTURED, MAGAZINE, x - 12, y + 2, 0, 0, 12, 12, 12, 12);
			int sx = x - 14 - font.width(small);
			g.text(font, small, sx, y + 4, 0xFFE0E0E0, true);
			g.text(font, "|", sx - 8, y + 4, 0xFF9A9A9A, true);

			// rounds in the magazine, drawn at 2x
			var pose = g.pose();
			pose.pushMatrix();
			int bx = sx - 12 - font.width(big) * 2;
			pose.translate(bx, y);
			pose.scale(2f, 2f);
			g.text(font, big, 0, 0, lowColor, true);
			pose.popMatrix();
			// CS2's fire mode icon beside the rounds: three bullets in burst mode, the full-auto icon, else one bullet
			Identifier mode = w.q().burst() && gun.burst(mc.player.getMainHandItem()) ? BURST : w.fullAuto() ? FULLAUTO : BULLET;
			g.blit(RenderPipelines.GUI_TEXTURED, mode, bx - 16, y + 2, 0, 0, 12, 12, 12, 12);
			if (GunClient.INSTANCE.reloading()) g.text(font, "RELOADING", sx - 60, y - 10, 0xFFFFC864, true);
			double prog = GunClient.INSTANCE.reloadProgress();
			if (prog >= 0 && dev.strafemc.client.Placeholders.active()) { // no reload animation without CS2 files: show the progress
				int bw = 60, bx0 = sx - 60, by0 = y - 13;
				g.fill(bx0, by0, bx0 + bw, by0 + 2, 0x80000000);
				g.fill(bx0, by0, bx0 + (int) (bw * prog), by0 + 2, 0xFFFFC864);
			}
		});
	}
}
