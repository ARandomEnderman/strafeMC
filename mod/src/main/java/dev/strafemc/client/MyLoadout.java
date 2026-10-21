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
			if (sendAt != 0 && System.currentTimeMillis() >= sendAt) {
				sendAt = 0;
				send();
			}
		});
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

	public static void send() {
		if (ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.MenuLoadoutChoice.TYPE))
			ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.MenuLoadoutChoice(GSON.toJson(root())));
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

	/** Back to CS2's default buy menu for a side. */
	public static void resetBuy(boolean t) {
		obj(root(), "buy").remove(t ? "t" : "ct");
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
