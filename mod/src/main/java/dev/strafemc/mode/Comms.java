package dev.strafemc.mode;

import com.google.gson.JsonObject;
import dev.strafemc.data.RadioTable;
import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.CsTeam;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CS2's team comms: chat wheel messages (playerchatwheel: the phrase's text in your team's chat, your agent's voice line
 * for it on your team's radio) and pings (player_ping: a marker where you look, or on the player or bot you look at,
 * for your team, with UI.PlayerPing). Only allow-listed phrases go out (sv_playerradio_use_allowlist 1). Limits from
 * CS2: 3 radio messages per sv_radio_throttle_window (10 s), 5 pings each back after player_ping_token_cooldown (20 s);
 * a new ping replaces your last one. Teams: your side's players in the running mode (alone in a free-for-all), else
 * everyone on your side. FLAGGED ours: a ping lasts PING_LIFE, reaches PING_RANGE, pinging again within URGENT_TICKS
 * close to your last ping makes it urgent (UI.PlayerPingUrgent); dead players can't use either.
 */
public final class Comms {
	private static final int RADIO_TOKENS = 3, RADIO_WINDOW = 10 * 20, PING_TOKENS = 5, PING_COOLDOWN = 20 * 20;
	private static final int PING_LIFE = 8 * 20, URGENT_TICKS = 12;
	private static final double PING_RANGE = 256, URGENT_NEAR = 3;
	/** Per player: the game ticks their tokens are free again. */
	private static final Map<UUID, long[]> RADIO = new HashMap<>(), PING = new HashMap<>();
	/** Per player: their last ping (x, y, z, tick). */
	private static final Map<UUID, double[]> LAST_PING = new HashMap<>();

	private Comms() {}

	/** playerchatwheel: a chat wheel phrase by its radio name (CW.NeedDrop). */
	public static void radio(ServerPlayer p, String radio) {
		RadioTable.Phrase ph = RadioTable.phrase(radio);
		if (ph == null || !p.isAlive() || p.isSpectator() || !token(RADIO, p, RADIO_TOKENS, RADIO_WINDOW)) return;
		boolean t = CsTeam.isT(p);
		JsonObject o = new JsonObject();
		o.addProperty("k", "radio");
		o.addProperty("id", p.getId());
		o.addProperty("name", p.getScoreboardName());
		o.addProperty("radio", ph.radio());
		o.addProperty("text", ph.text());
		o.addProperty("voice", RadioTable.voice(dev.strafemc.weapon.MenuLoadout.agent(p, t), t));
		o.addProperty("t", t);
		colour(o, p);
		send(p, o);
	}

	/** player_ping: what you look at, for your team. */
	public static void ping(ServerPlayer p) {
		if (!p.isAlive() || p.isSpectator()) return;
		long now = p.level().getGameTime();
		if (!token(PING, p, PING_TOKENS, PING_COOLDOWN)) {
			JsonObject o = new JsonObject(); // the client shows CS2's #Chatwheel_ping_on_cooldown
			o.addProperty("k", "cooldown");
			if (ServerPlayNetworking.canSend(p, StrafePayloads.Comms.TYPE)) ServerPlayNetworking.send(p, new StrafePayloads.Comms(o.toString()));
			return;
		}
		Vec3 eye = p.getEyePosition(), look = p.getViewVector(1);
		Vec3 end = eye.add(look.scale(PING_RANGE));
		HitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		Vec3 at = block.getType() == HitResult.Type.MISS ? null : block.getLocation();
		double reach = at == null ? PING_RANGE : eye.distanceTo(at);
		// a player or bot in the way: the ping stays on them
		EntityHitResult hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(p.level(), p, eye, eye.add(look.scale(reach)),
				new AABB(eye, eye.add(look.scale(reach))).inflate(1), e -> e instanceof LivingEntity le && le.isAlive() && !e.isSpectator() && e != p, 0.3f);
		Entity on = hit == null ? null : hit.getEntity();
		if (on != null) at = new Vec3(on.getX(), on.getY() + on.getBbHeight(), on.getZ());
		if (at == null) return; // the sky: CS2 has no ping there either
		double[] last = LAST_PING.get(p.getUUID());
		boolean urgent = last != null && now - last[3] <= URGENT_TICKS && at.distanceTo(new Vec3(last[0], last[1], last[2])) <= URGENT_NEAR;
		LAST_PING.put(p.getUUID(), new double[] {at.x, at.y, at.z, now});
		JsonObject o = new JsonObject();
		o.addProperty("k", "ping");
		o.addProperty("id", p.getId());
		o.addProperty("name", p.getScoreboardName());
		o.addProperty("x", at.x);
		o.addProperty("y", at.y);
		o.addProperty("z", at.z);
		o.addProperty("on", on == null ? -1 : on.getId());
		o.addProperty("urgent", urgent);
		o.addProperty("life", PING_LIFE / 20.0);
		o.addProperty("t", CsTeam.isT(p));
		colour(o, p);
		send(p, o);
	}

	/** Takes one of the player's tokens (free again after `cooldown` ticks); false when all are in use. */
	private static boolean token(Map<UUID, long[]> all, ServerPlayer p, int count, int cooldown) {
		long now = p.level().getGameTime();
		long[] slots = all.computeIfAbsent(p.getUUID(), k -> new long[count]);
		for (int i = 0; i < slots.length; i++)
			if (slots[i] <= now) {
				slots[i] = now + cooldown;
				return true;
			}
		return false;
	}

	/** The sender's teammate colour in Competitive / Wingman ("c": 0-4), else none (the side's colour). */
	private static void colour(JsonObject o, ServerPlayer p) {
		Mode m = GameModes.running();
		if (m == null) return;
		Integer c = Radar.colours(m, m.participants()).get(p);
		if (c != null) o.addProperty("c", c);
	}

	private static void send(ServerPlayer from, JsonObject o) {
		String json = o.toString();
		for (ServerPlayer to : team(from))
			if (ServerPlayNetworking.canSend(to, StrafePayloads.Comms.TYPE)) ServerPlayNetworking.send(to, new StrafePayloads.Comms(json));
	}

	/** Who hears a player's comms: their own side in the same world (just them in a free-for-all). */
	private static List<ServerPlayer> team(ServerPlayer p) {
		Mode m = GameModes.running();
		List<ServerPlayer> out = new ArrayList<>();
		if (m != null && m.ffa()) {
			out.add(p);
			return out;
		}
		boolean t = CsTeam.isT(p);
		List<ServerPlayer> pool = m != null && Mode.inMode(p) ? m.players() : p.level().getServer().getPlayerList().getPlayers();
		for (ServerPlayer o : pool) if (o.level() == p.level() && CsTeam.isT(o) == t) out.add(o);
		return out;
	}

	public static void forget(ServerPlayer p) {
		RADIO.remove(p.getUUID());
		PING.remove(p.getUUID());
		LAST_PING.remove(p.getUUID());
	}
}
