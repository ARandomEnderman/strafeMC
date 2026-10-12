package dev.strafemc.client;

import dev.strafemc.data.AssetCache;
import dev.strafemc.net.StrafePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Crude stand-ins for players without CS2 files (e.g. joining a friend's server): the game plays by the server's rules
 * either way; these just keep it readable. Vanilla sounds for shots / knife / reload foley, vanilla block particles for
 * bullet impacts, and simple drawn item icons. Nothing here is derived from Valve's assets.
 */
public final class Placeholders {
	private Placeholders() {}

	/** True when this client has no usable CS2 install (no pak or no converter): use the placeholders. */
	public static boolean active() {
		return !AssetCache.available();
	}

	/** A vanilla stand-in for a CS2 sound event, by what kind of event it is (null = stay silent). */
	public static boolean sound(String event, double x, double y, double z, double gain) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || event == null) return false;
		String e = event.toLowerCase();
		SoundEvent s;
		float vol = 1, pitch = 1;
		if (e.endsWith(".single") || e.contains("shoot")) {
			s = SoundEvents.FIREWORK_ROCKET_BLAST;
			pitch = 1.4f;
		} else if (e.contains("knife.hit") || e.contains("backstab")) s = SoundEvents.PLAYER_ATTACK_STRONG;
		else if (e.contains("hitwall")) s = SoundEvents.PLAYER_ATTACK_KNOCKBACK;
		else if (e.contains("swish")) s = SoundEvents.PLAYER_ATTACK_SWEEP;
		else if (e.contains("clipempty")) {
			s = SoundEvents.DISPENSER_FAIL;
			vol = 0.5f;
		} else if (e.contains("clip") || e.contains("reload") || e.contains("bolt") || e.contains("draw") || e.contains("deploy")) {
			s = SoundEvents.ARMOR_EQUIP_IRON.value();
			vol = 0.5f;
		} else return false;
		mc.level.playLocalSound(x, y, z, s, SoundSource.PLAYERS, (float) (vol * gain), pitch, false);
		return true;
	}

	/** Reload start (a magazine coming out) and end (the new one going in), for players who hear no CS2 clip foley. */
	public static void reloadSound(net.minecraft.world.entity.player.Player p, boolean end) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		mc.level.playLocalSound(p.getX(), p.getEyeY(), p.getZ(), end ? SoundEvents.CROSSBOW_LOADING_END.value() : SoundEvents.ARMOR_EQUIP_IRON.value(),
				SoundSource.PLAYERS, 0.7f, end ? 1.3f : 0.9f, false);
	}

	/** Vanilla block-break particles and hit sound where a bullet landed. */
	public static void impact(StrafePayloads.BulletImpact m) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		BlockState st = mc.level.getBlockState(BlockPos.of(m.block()));
		if (st.isAir()) return;
		var opt = new BlockParticleOption(ParticleTypes.BLOCK, st);
		for (int i = 0; i < 6; i++)
			mc.level.addParticle(opt, m.x(), m.y(), m.z(), m.nx() * 0.1 + (Math.random() - 0.5) * 0.1, m.ny() * 0.1 + Math.random() * 0.1,
					m.nz() * 0.1 + (Math.random() - 0.5) * 0.1);
		var snd = st.getSoundType();
		mc.level.playLocalSound(m.x(), m.y(), m.z(), snd.getHitSound(), SoundSource.BLOCKS, 0.6f, snd.getPitch() * 1.2f, false);
	}

	public enum Icon { GUN, KNIFE, MAG, BULLET, FULLAUTO }

	/** A simple drawn icon (side view silhouettes in grey), used when the CS2 icon can't be converted. */
	public static BufferedImage icon(Icon kind, int size) {
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.scale(size / 16.0, size / 16.0);
		g.setColor(new Color(0x3A3A3A));
		switch (kind) {
			case GUN -> {
				g.fillRect(1, 6, 11, 2); // body
				g.fillRect(12, 6, 3, 1); // barrel
				g.fillRect(5, 8, 2, 4); // magazine
				g.fillRect(0, 7, 2, 3); // stock
				g.setColor(new Color(0x7A4A24));
				g.fillRect(8, 8, 2, 2); // grip
			}
			case KNIFE -> {
				g.setColor(new Color(0xB8B8B8));
				g.fillPolygon(new int[] {6, 15, 6}, new int[] {6, 8, 9}, 3); // blade
				g.setColor(new Color(0x2A2A2A));
				g.fillRect(1, 7, 5, 2); // handle
			}
			case MAG -> g.fillRect(5, 2, 6, 12);
			case BULLET -> {
				g.setColor(new Color(0xC8A040));
				g.fillRect(6, 4, 4, 10);
				g.fillOval(6, 2, 4, 4);
			}
			case FULLAUTO -> {
				g.setColor(new Color(0xC8A040));
				for (int i = 0; i < 3; i++) g.fillRect(2 + i * 5, 4, 3, 9);
			}
		}
		g.dispose();
		return img;
	}
}
