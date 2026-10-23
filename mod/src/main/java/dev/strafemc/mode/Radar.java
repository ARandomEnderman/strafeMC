package dev.strafemc.mode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.CsTeam;
import dev.strafemc.weapon.WeaponItems;
import dev.strafemc.world.BombSiteBlock;
import dev.strafemc.world.SpawnPoints;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CS2's radar (hudradar), the server's half: what each player's radar may show, sent five times a second. Teammates
 * always (their side's colour, a cross where they died until they're back); enemies only while spotted: in sight of the
 * player or a living teammate (in Deathmatch and Arms Race, of the player alone); the bomb to Terrorists wherever it is,
 * to CTs once one of them has it in sight; the bomb sites. Nobody else's position reaches a client's radar.
 * Spotted = on the spotter's screen: inside the view (CS2's default 90 deg field of view on a 16:9 screen, about 53
 * deg either side; Minecraft's default 70 deg up and down, 35 either way), within SPOT_RANGE, with a clear line from
 * their eyes that no smoke crosses. FLAGGED ours: CS2 spots from its own visibility checks; the real players' own
 * field of view and screen shape aren't known to the server. In Competitive and Wingman each player has a teammate colour (CS2's cl_teammate_color_1-5:
 * blue, green, yellow, orange, purple), handed out in each side's order (by name: FLAGGED, CS2 by lobby slot).
 */
final class Radar {
	private static final double SPOT_RANGE = 96, HALF_W = Math.tan(Math.toRadians(53.13)), HALF_H = Math.tan(Math.toRadians(35));
	private static final int EVERY = 4;
	/** Where participants died (by their key), until they live again. */
	private static final Map<UUID, Vec3> DIED = new HashMap<>();

	private Radar() {}

	static void tick(Mode mode) {
		if (mode.now() % EVERY != 0) return;
		List<LivingEntity> all = mode.participants();
		for (LivingEntity e : all) {
			if (e.isAlive() && !e.isSpectator()) DIED.remove(Mode.key(e));
			else if (!DIED.containsKey(Mode.key(e)) && !e.isSpectator()) DIED.put(Mode.key(e), e.position());
		}
		DIED.keySet().removeIf(k -> all.stream().noneMatch(e -> Mode.key(e).equals(k)));
		List<LivingEntity> alive = all.stream().filter(e -> e.isAlive() && !e.isSpectator()).toList();
		// who each side has in sight (both sides: the round modes; everyone alone: free for all)
		Map<Boolean, Set<LivingEntity>> sideSees = new HashMap<>();
		if (!mode.ffa())
			for (boolean t : new boolean[] {true, false}) {
				Set<LivingEntity> seen = new HashSet<>();
				for (LivingEntity s : alive)
					if (CsTeam.isT(s) == t) for (LivingEntity o : alive) if (CsTeam.isT(o) != t && !seen.contains(o) && sees(s, o)) seen.add(o);
				sideSees.put(t, seen);
			}
		Entity planted = mode.plantedBomb();
		ItemEntity dropped = null;
		LivingEntity carrier = null;
		for (LivingEntity e : alive) if (mode.carriesBomb(e)) carrier = e;
		if (carrier == null && planted == null)
			for (ServerLevel level : levels(alive)) {
				for (ItemEntity i : level.getEntities(net.minecraft.world.entity.EntityType.ITEM, i -> i.getItem().is(WeaponItems.C4))) dropped = i;
			}
		Entity bombAt = planted != null ? planted : dropped != null ? dropped : carrier;
		boolean ctSeesBomb = false;
		if (bombAt != null && !mode.ffa())
			for (LivingEntity s : alive) if (!CsTeam.isT(s) && sees(s, bombAt)) ctSeesBomb = true;
		JsonArray sites = sites();
		boolean colours = mode.id().equals("competitive") || mode.id().equals("wingman");
		Map<LivingEntity, Integer> colour = new HashMap<>();
		if (colours)
			for (boolean t : new boolean[] {true, false}) {
				List<LivingEntity> side = new java.util.ArrayList<>(all.stream().filter(e -> CsTeam.isT(e) == t).toList());
				side.sort(java.util.Comparator.comparing(Mode::name));
				for (int i = 0; i < side.size(); i++) colour.put(side.get(i), i % 5);
			}
		for (ServerPlayer p : mode.players()) {
			if (!ServerPlayNetworking.canSend(p, StrafePayloads.RadarState.TYPE)) continue;
			boolean t = CsTeam.isT(p);
			JsonObject o = new JsonObject();
			if (colour.containsKey(p)) o.addProperty("me", colour.get(p));
			JsonArray list = new JsonArray();
			for (LivingEntity e : all) {
				if (e == p) continue;
				boolean mate = !mode.ffa() && CsTeam.isT(e) == t;
				boolean living = e.isAlive() && !e.isSpectator();
				if (mate) {
					Vec3 at = living ? e.position() : DIED.get(Mode.key(e));
					if (at != null) {
						JsonObject x = entry(e, at, living, false, living && e == carrier);
						if (colour.containsKey(e)) x.addProperty("c", colour.get(e));
						list.add(x);
					}
				} else if (living && e.level() == p.level() && (mode.ffa() ? p.isAlive() && sees(p, e) : sideSees.get(t).contains(e)))
					list.add(entry(e, e.position(), true, true, false));
			}
			o.add("p", list);
			if (bombAt != null && (mode.ffa() ? false : t || ctSeesBomb) && bombAt.level() == p.level()) {
				JsonObject b = new JsonObject();
				b.addProperty("x", bombAt.getX());
				b.addProperty("y", bombAt.getY());
				b.addProperty("z", bombAt.getZ());
				b.addProperty("s", planted != null ? "planted" : dropped != null ? "dropped" : "carried");
				o.add("bomb", b);
			}
			if (!mode.ffa()) o.add("sites", sites);
			ServerPlayNetworking.send(p, new StrafePayloads.RadarState(o.toString()));
		}
	}

	private static JsonObject entry(LivingEntity e, Vec3 at, boolean alive, boolean enemy, boolean bomb) {
		JsonObject x = new JsonObject();
		x.addProperty("id", e.getId());
		x.addProperty("x", Math.round(at.x * 10) / 10.0);
		x.addProperty("y", Math.round(at.y * 10) / 10.0);
		x.addProperty("z", Math.round(at.z * 10) / 10.0);
		x.addProperty("yaw", Math.round(e.getYHeadRot()));
		x.addProperty("t", CsTeam.isT(e));
		if (!alive) x.addProperty("dead", true);
		if (enemy) x.addProperty("enemy", true);
		if (bomb) x.addProperty("bomb", true);
		return x;
	}

	/** In sight of s: near enough, within its view and nothing solid between their eyes (or its middle). */
	private static boolean sees(LivingEntity s, Entity o) {
		if (s.level() != o.level() || s.distanceToSqr(o) > SPOT_RANGE * SPOT_RANGE) return false;
		Vec3 eye = s.getEyePosition();
		// the spotter's view: forward, right and up
		Vec3 f = s.getLookAngle(), r = f.cross(new Vec3(0, 1, 0));
		if (r.lengthSqr() < 1e-6) r = new Vec3(1, 0, 0);
		r = r.normalize();
		Vec3 up = r.cross(f).normalize();
		for (Vec3 to : new Vec3[] {o.getEyePosition(), o.getBoundingBox().getCenter()}) {
			Vec3 d = to.subtract(eye);
			double z = d.dot(f);
			if (z <= 0.05 || Math.abs(d.dot(r)) / z > HALF_W || Math.abs(d.dot(up)) / z > HALF_H) continue; // off the screen
			if (s.level().clip(new ClipContext(eye, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, s)).getType() != HitResult.Type.MISS) continue;
			if (dev.strafemc.weapon.GrenadeZones.throughSmoke(s.level(), eye, to)) continue; // smoke hides them, as in CS2
			return true;
		}
		return false;
	}

	private static Set<ServerLevel> levels(List<LivingEntity> es) {
		Set<ServerLevel> out = new HashSet<>();
		for (LivingEntity e : es) if (e.level() instanceof ServerLevel l) out.add(l);
		return out;
	}

	/** The bomb sites: drawn zones' middles, else the site blocks' floors, with their letters. */
	private static JsonArray sites() {
		JsonArray out = new JsonArray();
		for (Zones.Zone z : Zones.all())
			if (z.type().site()) out.add(site(z.type() == Zones.Type.SITE_A ? "A" : "B", z.floorCentre()));
		if (out.isEmpty())
			for (SpawnPoints.Marker m : SpawnPoints.markers())
				if (m.state().getBlock() instanceof BombSiteBlock)
					out.add(site(m.state().getValue(BombSiteBlock.SITE) == BombSiteBlock.Site.A ? "A" : "B", SpawnPoints.standOn(m.level(), m.pos())));
		return out;
	}

	private static JsonObject site(String letter, Vec3 at) {
		JsonObject s = new JsonObject();
		s.addProperty("l", letter);
		s.addProperty("x", at.x);
		s.addProperty("y", at.y);
		s.addProperty("z", at.z);
		return s;
	}

	static void reset() {
		DIED.clear();
	}
}
