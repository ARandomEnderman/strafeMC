package dev.strafemc.weapon;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.strafemc.StrafeMc;
import dev.strafemc.server.ServerRules;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The loadout players pick in strafeMC's main menu, before joining: a finish (wear, pattern, StatTrak) for every gun, a
 * knife and its finish, gloves, and which weapons CS2's buy menu shows in each slot for each side. Clients send it on
 * joining and on every change; the server checks it, keeps the buy menu part and builds the skins as wraps and gloves
 * (MenuLoadout.DATA, synced to everyone). While the server uses menu loadouts (ServerRules.menuLoadout) these are what
 * players wear (Loadout.worn) instead of their loadout items, which stay where they are.
 */
public final class MenuLoadout {
	private static final Gson GSON = new Gson();

	/** Buy menu columns with loadout slots: pistols, mid-tier (SMGs and heavy) and rifles, five each, as CS2's. */
	public static final List<String> CATEGORIES = List.of("secondary", "smg", "rifle");
	public static final int SLOTS = 5;

	/** CS2's starting pistols, the only ones its first pistol slot takes. */
	public static final List<String> START_T = List.of("weapon_glock"), START_CT = List.of("weapon_hkp2000", "weapon_usp_silencer");

	/** Slot id -> the built wrap or gloves (same ids as Loadout: a gun's class, "knife", "gloves"). Rebuilt each join. */
	public static final AttachmentType<Map<String, ItemStack>> DATA = AttachmentRegistry.create(Identifier.fromNamespaceAndPath("strafemc", "menu_loadout"),
			b -> b.copyOnDeath().initializer(Map::of)
					.syncWith(ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ItemStack.STREAM_CODEC), AttachmentSyncPredicate.all()));

	/** On the client: the server it's on uses menu loadouts (from the gameplay sync). */
	public static volatile boolean client;

	/** What each player chose besides skins: the knife and the buy menu slots per side ("t"/"ct" -> slot -> class). */
	private record Choice(String knife, Map<String, Map<String, String>> buy) {}

	private static final Map<UUID, Choice> CHOICES = new ConcurrentHashMap<>();
	private static MinecraftServer server;

	private MenuLoadout() {}

	public static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			server = null;
			CHOICES.clear();
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> CHOICES.remove(h.getPlayer().getUUID()));
	}

	/** Whether this player wears their menu loadout: the server's setting (on the client, as synced). */
	public static boolean on(Player p) {
		return p.level().isClientSide() ? client : ServerRules.menuLoadout();
	}

	public static ItemStack get(Player p, String slot) {
		ItemStack s = p.getAttachedOrElse(DATA, Map.of()).get(slot);
		return s == null ? ItemStack.EMPTY : s;
	}

	/** A StatTrak kill counted on a menu-loadout wrap (for this visit to the server only). */
	static void set(Player p, String slot, ItemStack stack) {
		Map<String, ItemStack> m = new HashMap<>(p.getAttachedOrElse(DATA, Map.of()));
		if (stack.isEmpty()) m.remove(slot);
		else m.put(slot, stack.copy());
		p.setAttached(DATA, Map.copyOf(m));
	}

	/** The menu loadout's switch changed: the knives players hold don't change, but menus and wraps do (sync). */
	public static void refreshAll() {
		if (server == null) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) p.setAttached(DATA, Map.copyOf(p.getAttachedOrElse(DATA, Map.of())));
	}

	/** Weapons a side can put in a buy menu category ("secondary", "smg", "rifle"), in CS2's buy menu order. */
	public static List<String> eligible(String category, boolean t) {
		List<String> out = new ArrayList<>();
		for (WeaponTable.Entry e : WeaponTable.guns())
			if (e.slot() != null && e.slot().matches(category + "\\d") && (t ? e.forT() : e.forCt())) out.add(e.cls());
		out.sort(java.util.Comparator.comparingInt(WeaponTable::order));
		return out;
	}

	/** Whether a weapon may go in a buy menu slot ("secondary0".."rifle4") for a side: its category, and the first pistol slot only for starting pistols. */
	public static boolean fits(String slot, String cls, boolean t) {
		if (slot == null || cls == null || slot.length() < 2) return false;
		String category = slot.substring(0, slot.length() - 1);
		if (!CATEGORIES.contains(category) || !eligible(category, t).contains(cls)) return false;
		boolean starter = (t ? START_T : START_CT).contains(cls), first = slot.equals("secondary0");
		return category.equals("secondary") ? starter == first : true;
	}

	/** The weapon in a player's buy menu slot for a side: their choice if it fits, else CS2's default (WeaponTable.inSlot). */
	public static String buySlot(Player p, String slot, boolean t) {
		Choice c = p == null ? null : CHOICES.get(p.getUUID());
		String cls = c == null ? null : c.buy.getOrDefault(t ? "t" : "ct", Map.of()).get(slot);
		if (cls != null && fits(slot, cls, t)) return cls;
		WeaponTable.Entry e = WeaponTable.inSlot(slot, t);
		return e == null ? null : e.cls();
	}

	/**
	 * Whether a weapon is in one of the player's buy menu slots for their side, as CS2 only sells what your loadout
	 * shows. Weapons outside the three slotted columns (the Zeus) always are.
	 */
	public static boolean inBuyMenu(Player p, String cls) {
		WeaponTable.Entry e = WeaponTable.get(cls);
		if (e == null || e.slot() == null) return true;
		String category = e.slot().substring(0, e.slot().length() - 1); // "rifle3" -> "rifle"
		if (!CATEGORIES.contains(category)) return true;
		boolean t = CsTeam.isT(p);
		for (int i = 0; i < SLOTS; i++) if (cls.equals(buySlot(p, category + i, t))) return true;
		return false;
	}

	/** The pistol a player starts a round with: their side's first pistol slot (CS2: the Glock, or the P2000 / USP-S). */
	public static String startPistol(Player p) {
		boolean t = CsTeam.isT(p);
		String cls = buySlot(p, "secondary0", t);
		return cls != null ? cls : t ? "weapon_glock" : "weapon_hkp2000";
	}

	/** The knife a player gets: their menu loadout's knife while the server uses menu loadouts, else their side's. */
	public static ItemStack knife(Player p) {
		Choice c = CHOICES.get(p.getUUID());
		String cls = on(p) && c != null && c.knife != null ? c.knife : CsTeam.isT(p) ? "weapon_knife_t" : "weapon_knife";
		var item = WeaponItems.KNIVES.get(cls);
		return new ItemStack(item != null ? item : WeaponItems.KNIVES.get(CsTeam.isT(p) ? "weapon_knife_t" : "weapon_knife"));
	}

	/**
	 * A client's menu loadout (JSON, kept by the client in menu_loadout.json): {"knife": cls, "buy": {"t": {slot: cls}, "ct": {...}},
	 * "skins": {cls or "knife": {"wrap", "wear", "seed", "st"}, "gloves": {"kit", "wear"}}}. Anything that doesn't fit
	 * is left out.
	 */
	public static void receive(ServerPlayer p, String json) {
		JsonObject o;
		try {
			o = GSON.fromJson(json, JsonObject.class);
		} catch (Exception e) {
			return;
		}
		if (o == null) return;
		String knife = str(o, "knife");
		if (knife != null && (!WeaponItems.KNIVES.containsKey(knife) || !WeaponTable.get(knife).knife())) knife = null;
		Map<String, Map<String, String>> buy = new HashMap<>();
		JsonObject b = o.has("buy") && o.get("buy").isJsonObject() ? o.getAsJsonObject("buy") : new JsonObject();
		for (String side : new String[] {"t", "ct"}) {
			Map<String, String> m = new HashMap<>();
			if (b.has(side) && b.get(side).isJsonObject())
				for (var e : b.getAsJsonObject(side).entrySet())
					if (e.getValue().isJsonPrimitive() && fits(e.getKey(), e.getValue().getAsString(), side.equals("t"))) m.put(e.getKey(), e.getValue().getAsString());
			buy.put(side, Map.copyOf(m));
		}
		CHOICES.put(p.getUUID(), new Choice(knife, Map.copyOf(buy)));
		// in a mode the knife held is the mode's (never the player's own): the chosen one takes its place at once
		if (on(p) && dev.strafemc.mode.GameModes.playing(p)) {
			ItemStack k = knife(p);
			var inv = p.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++)
				if (inv.getItem(i).getItem() instanceof KnifeItem && !inv.getItem(i).is(k.getItem())) inv.setItem(i, k.copy());
		}
		Map<String, ItemStack> built = new HashMap<>();
		JsonObject skins = o.has("skins") && o.get("skins").isJsonObject() ? o.getAsJsonObject("skins") : new JsonObject();
		for (var e : skins.entrySet()) {
			if (!e.getValue().isJsonObject()) continue;
			JsonObject s = e.getValue().getAsJsonObject();
			String slot = e.getKey();
			try {
				ItemStack stack = build(slot, s);
				if (!stack.isEmpty()) built.put(slot, stack);
			} catch (Exception ex) {
				StrafeMc.LOG.debug("[strafemc] menu loadout: {} skipped ({})", slot, ex.toString());
			}
		}
		// StatTrak counts made on this visit stay with the same finish
		Map<String, ItemStack> before = p.getAttachedOrElse(DATA, Map.of());
		for (var e : built.entrySet()) {
			ItemStack old = before.get(e.getKey());
			Integer kills = old == null ? null : old.get(WeaponItems.STATTRAK);
			if (kills != null && e.getValue().has(WeaponItems.STATTRAK) && SkinWrapItem.finish(old) == SkinWrapItem.finish(e.getValue())
					&& java.util.Objects.equals(SkinWrapItem.skin(old), SkinWrapItem.skin(e.getValue())))
				e.getValue().set(WeaponItems.STATTRAK, kills);
		}
		p.setAttached(DATA, Map.copyOf(built));
	}

	/** One skin entry as the item it stands for (the client's previews use the same), or EMPTY if it doesn't fit. */
	public static ItemStack build(String slot, JsonObject s) {
		return slot.equals("gloves") ? gloves(s) : wrap(slot, s);
	}

	/** A gun's (slot = its class) or the knife's ("knife") finish as a wrap. */
	private static ItemStack wrap(String slot, JsonObject s) {
		String id = str(s, "wrap");
		SkinWraps.Wrap w = SkinWraps.get(id);
		if (w == null || w.hidden()) return ItemStack.EMPTY;
		boolean knife = slot.equals("knife");
		if (knife != w.knife() || !knife && !SkinWraps.fits(id, slot)) return ItemStack.EMPTY;
		String kit = knife ? SkinWraps.iconKit(w) : SkinWraps.kitFor(id, slot);
		double[] r = Wear.range(kit);
		float wear = (float) Math.max(r[0], Math.min(r[1], num(s, "wear", r[0])));
		int seed = (int) Math.max(0, Math.min(1000, num(s, "seed", 0)));
		ItemStack stack = SkinWrapItem.stack(knife ? WeaponItems.KNIFE_WRAP : WeaponItems.GUN_WRAP, id, seed, wear);
		if (s.has("st") && s.get("st").isJsonPrimitive() && s.get("st").getAsBoolean()) stack.set(WeaponItems.STATTRAK, 0);
		return stack;
	}

	private static ItemStack gloves(JsonObject s) {
		GloveSkins.Skin skin = GloveSkins.get(str(s, "kit"));
		if (skin == null) return ItemStack.EMPTY;
		double[] r = Wear.range(skin.kit());
		float wear = (float) Math.max(r[0], Math.min(r[1], num(s, "wear", r[0])));
		return GloveItem.stack(WeaponItems.GLOVES.get(skin.kind()), skin.kit(), wear);
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
	}

	private static double num(JsonObject o, String k, double def) {
		try {
			return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
		} catch (NumberFormatException e) {
			return def;
		}
	}
}
