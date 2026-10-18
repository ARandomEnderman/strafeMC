package dev.strafemc.weapon;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;

/** Running counts kept with the player for the counting advancements: containers opened, kills with CS weapons. */
public final class Stats {
	public static final String OPENED = "opened", KILLS = "kills";

	private static final AttachmentType<Map<String, Integer>> DATA = AttachmentRegistry.create(Identifier.fromNamespaceAndPath("strafemc", "stats"),
			b -> b.persistent(Codec.unboundedMap(Codec.STRING, Codec.INT)).copyOnDeath().initializer(Map::of));

	private Stats() {}

	public static void register() {
		// the attachment registers when this class loads
	}

	/** Adds to a count; the new total. */
	static int add(ServerPlayer p, String key, int n) {
		Map<String, Integer> m = new HashMap<>(p.getAttachedOrElse(DATA, Map.of()));
		int v = (int) Math.min(Integer.MAX_VALUE, (long) m.getOrDefault(key, 0) + n);
		m.put(key, v);
		p.setAttached(DATA, Map.copyOf(m));
		return v;
	}
}
