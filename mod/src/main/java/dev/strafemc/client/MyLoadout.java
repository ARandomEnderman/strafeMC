package dev.strafemc.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.weapon.MenuLoadout;
import dev.strafemc.weapon.WeaponTable;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Your loadout from strafeMC's main menu (MenuLoadoutScreen), kept in menu_loadout.json in the strafeMC folder: the
 * weapons in each buy menu slot per side, a finish for every gun, the knife and its finish, and gloves. The buy menu
 * always shows your slots; servers that use menu loadouts (MenuLoadout) dress you in the rest. Sent to the server on
 * joining and after each change.
 */
public final class MyLoadout {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = StrafeConfig.RUNTIME_DIR.resolve("menu_loadout.json");
	private static JsonObject root;
	/** When the last change is due to go to the server (sent once changes stop for a moment), 0: nothing waiting. */
	private static long sendAt;

	private MyLoadout() {}

	public static void register() {
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((h, sender, mc) -> send());
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player != null) takeKills(mc.player);
			if (sendAt != 0 && System.currentTimeMillis() >= sendAt) {
				sendAt = 0;
				send();
			}
		});
	}

	/**
	 * StatTrak kills a server counted on your finishes (its built wraps, synced back) go into this file, so the counts
	 * follow you to every server; only for the same finish, and only upwards (a reset here is sent before it counts).
	 */
	private static void takeKills(net.minecraft.world.entity.player.Player p) {
		boolean any = false;
		for (var e : p.getAttachedOrElse(MenuLoadout.DATA, java.util.Map.<String, net.minecraft.world.item.ItemStack>of()).entrySet()) {
			Integer kills = e.getValue().get(dev.strafemc.weapon.WeaponItems.STATTRAK);
			String key = killKey(e.getKey());
			if (kills == null || key == null || !key.endsWith("/" + dev.strafemc.weapon.SkinWrapItem.skin(e.getValue()))) continue;
			if (kills > kills(e.getKey())) {
				obj(root(), "kills").addProperty(key, kills);
				any = true;
			}
		}
		if (any) save();
	}

	/** Where a slot's chosen finish keeps its count ("weapon_ak47/aa_vertigo"): each finish its own, as each CS2 item has. */
	private static String killKey(String slot) {
		JsonObject s = skin(slot);
		return s == null || !s.has("wrap") || !s.get("wrap").isJsonPrimitive() ? null : slot + "/" + s.get("wrap").getAsString();
	}

	/** The chosen finish's StatTrak count (0 if none yet). */
	public static int kills(String slot) {
		String key = killKey(slot);
		JsonObject k = obj(root(), "kills");
		return key != null && k.has(key) && k.get(key).isJsonPrimitive() ? Math.max(0, k.get(key).getAsInt()) : 0;
	}

	/** Back to zero (sent to the server too). */
	public static void resetKills(String slot) {
		String key = killKey(slot);
		if (key == null) return;
		obj(root(), "kills").addProperty(key, 0);
		changed();
	}

	/** Whether a finish has StatTrak: its own choice, else on for guns (knives and gloves off). */
	public static boolean statTrak(String slot) {
		JsonObject s = skin(slot);
		if (s == null || slot.equals("gloves")) return false;
		if (s.has("st") && s.get("st").isJsonPrimitive()) return s.get("st").getAsBoolean();
		return !slot.equals("knife");
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(root()));
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] could not save {}: {}", FILE, e.toString());
		}
	}

	private static synchronized JsonObject root() {
		if (root == null) {
			try {
				root = Files.exists(FILE) ? GSON.fromJson(Files.readString(FILE), JsonObject.class) : null;
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] {} unreadable: {}", FILE, e.toString());
			}
			if (root == null) root = new JsonObject();
		}
		return root;
	}

	private static JsonObject obj(JsonObject o, String k) {
		if (!o.has(k) || !o.get(k).isJsonObject()) o.add(k, new JsonObject());
		return o.getAsJsonObject(k);
	}

	/** Saves and, when on a server, sends it shortly (a burst of clicks goes as one). */
	private static void changed() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(root()));
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] could not save {}: {}", FILE, e.toString());
		}
		sendAt = System.currentTimeMillis() + 400;
	}

	/** What goes to the server: the loadout and the agents picked (they're settings, so they're added here). */
	private static JsonObject withAgents() {
		JsonObject o = root().deepCopy();
		o.remove("kills");
		// each finish as the server builds it: its StatTrak (gun default on) and its count
		JsonObject skins = obj(o, "skins");
		for (String slot : new java.util.ArrayList<>(skins.keySet())) {
			if (!skins.get(slot).isJsonObject() || slot.equals("gloves")) continue;
			skins.getAsJsonObject(slot).addProperty("st", statTrak(slot));
			skins.getAsJsonObject(slot).addProperty("kills", kills(slot));
		}
		o.addProperty("agentT", StrafeConfig.agentT);
		o.addProperty("agentCT", StrafeConfig.agentCT);
		return o;
	}

	public static void send() {
		if (ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.MenuLoadoutChoice.TYPE))
			ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.MenuLoadoutChoice(GSON.toJson(withAgents())));
	}

	// buy menu

	/** The weapon in a buy menu slot ("secondary0".."rifle4") for a side: yours if it fits, else CS2's default. */
	public static String buy(String slot, boolean t) {
		JsonObject side = obj(obj(root(), "buy"), t ? "t" : "ct");
		String cls = side.has(slot) ? side.get(slot).getAsString() : null;
		if (cls != null && MenuLoadout.fits(slot, cls, t)) return cls;
		WeaponTable.Entry e = WeaponTable.inSlot(slot, t);
		return e == null ? null : e.cls();
	}

	/** Puts a weapon in a slot; if another slot of the side had it, the two swap (CS2 shows each weapon once). */
	public static void setBuy(String slot, boolean t, String cls) {
		if (!MenuLoadout.fits(slot, cls, t)) return;
		String category = slot.substring(0, slot.length() - 1), was = buy(slot, t);
		JsonObject side = obj(obj(root(), "buy"), t ? "t" : "ct");
		for (int i = 0; i < MenuLoadout.SLOTS; i++) {
			String other = category + i;
			if (!other.equals(slot) && cls.equals(buy(other, t))) {
				if (was != null && MenuLoadout.fits(other, was, t)) side.addProperty(other, was);
				else side.remove(other);
			}
		}
		side.addProperty(slot, cls);
		changed();
	}

	// skins

	/** A skin entry ("weapon_ak47", "knife", "gloves"): {"wrap"/"kit", "wear", "seed", "st"}, or null for the default look. */
	public static JsonObject skin(String slot) {
		JsonObject s = obj(root(), "skins");
		return s.has(slot) && s.get(slot).isJsonObject() ? s.getAsJsonObject(slot) : null;
	}

	/** Sets (null: clears) a skin entry. */
	public static void setSkin(String slot, JsonObject entry) {
		JsonObject s = obj(root(), "skins");
		if (entry == null) s.remove(slot);
		else s.add(slot, entry);
		changed();
	}

	/** The chosen knife's class, or null for the side's default knife. */
	public static String knife() {
		JsonObject r = root();
		return r.has("knife") && r.get("knife").isJsonPrimitive() ? r.get("knife").getAsString() : null;
	}

	public static void setKnife(String cls) {
		if (cls == null) root().remove("knife");
		else root().addProperty("knife", cls);
		changed();
	}
}
