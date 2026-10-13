package dev.strafemc.weapon;

/**
 * The CS2 grenades: their weapons.vdata class, viewmodel animation folder / file suffix, world model and sound-event
 * prefix. How each one goes off is in GrenadeEntity.
 */
public enum Grenade {
	HE("weapon_hegrenade", "grenade_hegrenade", "hegrenade", "weapons/models/grenade/hegrenade/weapon_hegrenade.vmdl_c", "HEGrenade", "he_grenade"),
	FLASHBANG("weapon_flashbang", "grenade_flashbang", "flashbang", "weapons/models/grenade/flashbang/weapon_flashbang.vmdl_c", "Flashbang", "flashbang_grenade"),
	SMOKE("weapon_smokegrenade", "grenade_smokegrenade", "smoke", "weapons/models/grenade/smokegrenade/weapon_smokegrenade.vmdl_c", "SmokeGrenade", "smoke_grenade"),
	MOLOTOV("weapon_molotov", "grenade_molotov", "molotov", "weapons/models/grenade/molotov/weapon_molotov.vmdl_c", "Molotov", "molotov"),
	INCENDIARY("weapon_incgrenade", "grenade_incendiary", "incendiary", "weapons/models/grenade/incendiary/weapon_incendiarygrenade.vmdl_c", "IncGrenade",
			"incendiary_grenade"),
	DECOY("weapon_decoy", "_default_grenade", "grenade", "weapons/models/grenade/decoy/weapon_decoy.vmdl_c", "Decoy", "decoy_grenade");

	/** inspectGraph: its viewmodel_inspects graph variant (inspect odds). */
	public final String cls, clipFolder, clipSuffix, model, sounds, inspectGraph;

	Grenade(String cls, String clipFolder, String clipSuffix, String model, String sounds, String inspectGraph) {
		this.cls = cls;
		this.clipFolder = clipFolder;
		this.clipSuffix = clipSuffix;
		this.model = model;
		this.sounds = sounds;
		this.inspectGraph = inspectGraph;
	}

	/** The item id ("weapon_hegrenade"). */
	public String id() {
		return cls;
	}

	public String clipPath(String anim) {
		return "animation/anims/viewmodel/grenade/" + clipFolder + "/" + anim + "_" + clipSuffix + ".vnmclip_c";
	}

	public static Grenade of(String cls) {
		for (Grenade g : values()) if (g.cls.equals(cls)) return g;
		return null;
	}
}
