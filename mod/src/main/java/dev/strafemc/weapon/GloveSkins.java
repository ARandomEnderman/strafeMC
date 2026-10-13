package dev.strafemc.weapon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every CS2 glove finish (items_game paint kits that have a glove inventory image): its paint kit name, which gloves
 * wear it and its composite material in the user's CS2 files (the client bakes the finish from it, see
 * client.viewmodel.GloveSkinBaker). Names shown in game come from the user's CS2 localization (runtime pack).
 */
public final class GloveSkins {
	public record Skin(String kit, GloveItem.Kind kind, String composite) {}

	public static final List<Skin> ALL = List.of(
			new Skin("bloodhound_black_silver", GloveItem.Kind.BLOODHOUND, "gloves/paints/bloodhound_black_silver.vcompmat_c"),
			new Skin("bloodhound_guerrilla", GloveItem.Kind.BLOODHOUND, "gloves/paints/bloodhound_guerrilla.vcompmat_c"),
			new Skin("bloodhound_metallic", GloveItem.Kind.BLOODHOUND, "gloves/paints/bloodhound_metallic.vcompmat_c"),
			new Skin("bloodhound_snakeskin_brass", GloveItem.Kind.BLOODHOUND, "gloves/paints/bloodhound_snakeskin_brass.vcompmat_c"),
			new Skin("glove_sport_beadwork_diamonds_purple", GloveItem.Kind.SPORT, "gloves/paints/volatile_02/glove_sport_beadwork_diamonds_purple.vcompmat_c"),
			new Skin("glove_sport_camo_winter", GloveItem.Kind.SPORT, "gloves/paints/volatile_02/glove_sport_camo_winter.vcompmat_c"),
			new Skin("glove_sport_flames_orange", GloveItem.Kind.SPORT, "gloves/paints/volatile_02/glove_sport_flames_orange.vcompmat_c"),
			new Skin("glove_sport_pinstripe_black", GloveItem.Kind.SPORT, "gloves/paints/volatile_02/glove_sport_pinstripe_black.vcompmat_c"),
			new Skin("glove_sport_racer_red", GloveItem.Kind.SPORT, "gloves/paints/volatile_02/glove_sport_racer_red.vcompmat_c"),
			new Skin("glove_sport_tech_gradient_bright", GloveItem.Kind.SPORT, "gloves/paints/volatile_02/glove_sport_tech_gradient_bright.vcompmat_c"),
			new Skin("glove_sport_velvet_red", GloveItem.Kind.SPORT, "gloves/paints/volatile_02/glove_sport_velvet_red.vcompmat_c"),
			new Skin("sporty_black_webbing_yellow", GloveItem.Kind.SPORT, "gloves/paints/sporty_black_webbing_yellow.vcompmat_c"),
			new Skin("sporty_blue_pink", GloveItem.Kind.SPORT, "gloves/paints/sporty_blue_pink.vcompmat_c"),
			new Skin("sporty_green", GloveItem.Kind.SPORT, "gloves/paints/sporty_green.vcompmat_c"),
			new Skin("sporty_houndstooth_red", GloveItem.Kind.SPORT, "gloves/paints/sporty_houndstooth_red.vcompmat_c"),
			new Skin("sporty_hunter", GloveItem.Kind.SPORT, "gloves/paints/sporty_hunter.vcompmat_c"),
			new Skin("sporty_jaguar", GloveItem.Kind.SPORT, "gloves/paints/sporty_jaguar.vcompmat_c"),
			new Skin("sporty_light_blue", GloveItem.Kind.SPORT, "gloves/paints/sporty_light_blue.vcompmat_c"),
			new Skin("sporty_military", GloveItem.Kind.SPORT, "gloves/paints/sporty_military.vcompmat_c"),
			new Skin("sporty_poison_frog_blue_white", GloveItem.Kind.SPORT, "gloves/paints/sporty_poison_frog_blue_white.vcompmat_c"),
			new Skin("sporty_poison_frog_red_green", GloveItem.Kind.SPORT, "gloves/paints/sporty_poison_frog_red_green.vcompmat_c"),
			new Skin("sporty_purple", GloveItem.Kind.SPORT, "gloves/paints/sporty_purple.vcompmat_c"),
			new Skin("sporty_slingshot", GloveItem.Kind.SPORT, "gloves/paints/sporty_slingshot.vcompmat_c"),
			new Skin("glove_driver_brocade_crane_red", GloveItem.Kind.DRIVER, "gloves/paints/volatile_02/glove_driver_brocade_crane_red.vcompmat_c"),
			new Skin("glove_driver_brocade_flower_red", GloveItem.Kind.DRIVER, "gloves/paints/volatile_02/glove_driver_brocade_flower_red.vcompmat_c"),
			new Skin("glove_driver_dragon_scale_red", GloveItem.Kind.DRIVER, "gloves/paints/volatile_02/glove_driver_dragon_scale_red.vcompmat_c"),
			new Skin("glove_driver_embroidered_flowers_teal", GloveItem.Kind.DRIVER, "gloves/paints/volatile_02/glove_driver_embroidered_flowers_teal.vcompmat_c"),
			new Skin("glove_driver_knit_argyle_green", GloveItem.Kind.DRIVER, "gloves/paints/volatile_02/glove_driver_knit_argyle_green.vcompmat_c"),
			new Skin("glove_driver_ostrich_purple", GloveItem.Kind.DRIVER, "gloves/paints/volatile_02/glove_driver_ostrich_purple.vcompmat_c"),
			new Skin("glove_driver_scallop_metallic_blue", GloveItem.Kind.DRIVER, "gloves/paints/volatile_02/glove_driver_scallop_metallic_blue.vcompmat_c"),
			new Skin("glove_driver_silk_dragon_white", GloveItem.Kind.DRIVER, "gloves/paints/volatile_02/glove_driver_silk_dragon_white.vcompmat_c"),
			new Skin("slick_black", GloveItem.Kind.DRIVER, "gloves/paints/slick_black.vcompmat_c"),
			new Skin("slick_jaguar_white", GloveItem.Kind.DRIVER, "gloves/paints/slick_jaguar_white.vcompmat_c"),
			new Skin("slick_jaguar_yellow", GloveItem.Kind.DRIVER, "gloves/paints/slick_jaguar_yellow.vcompmat_c"),
			new Skin("slick_military", GloveItem.Kind.DRIVER, "gloves/paints/slick_military.vcompmat_c"),
			new Skin("slick_plaid_purple", GloveItem.Kind.DRIVER, "gloves/paints/slick_plaid_purple.vcompmat_c"),
			new Skin("slick_red", GloveItem.Kind.DRIVER, "gloves/paints/slick_red.vcompmat_c"),
			new Skin("slick_rezan", GloveItem.Kind.DRIVER, "gloves/paints/slick_rezan.vcompmat_c"),
			new Skin("slick_snakeskin_white", GloveItem.Kind.DRIVER, "gloves/paints/slick_snakeskin_white.vcompmat_c"),
			new Skin("slick_snakeskin_yellow", GloveItem.Kind.DRIVER, "gloves/paints/slick_snakeskin_yellow.vcompmat_c"),
			new Skin("slick_stitched_black_orange", GloveItem.Kind.DRIVER, "gloves/paints/slick_stitched_black_orange.vcompmat_c"),
			new Skin("slick_stitched_black_white", GloveItem.Kind.DRIVER, "gloves/paints/slick_stitched_black_white.vcompmat_c"),
			new Skin("slick_stitched_green_grey", GloveItem.Kind.DRIVER, "gloves/paints/slick_stitched_green_grey.vcompmat_c"),
			new Skin("handwrap_camo_grey", GloveItem.Kind.HAND_WRAPS, "gloves/paints/handwrap_camo_grey.vcompmat_c"),
			new Skin("handwrap_fabric_houndstooth_orange", GloveItem.Kind.HAND_WRAPS, "gloves/paints/handwrap_fabric_houndstooth_orange.vcompmat_c"),
			new Skin("handwrap_fabric_orange_camo", GloveItem.Kind.HAND_WRAPS, "gloves/paints/handwrap_fabric_orange_camo.vcompmat_c"),
			new Skin("handwrap_leathery", GloveItem.Kind.HAND_WRAPS, "gloves/paints/handwrap_leathery.vcompmat_c"),
			new Skin("handwrap_leathery_caution", GloveItem.Kind.HAND_WRAPS, "gloves/paints/handwrap_leathery_caution.vcompmat_c"),
			new Skin("handwrap_leathery_ducttape", GloveItem.Kind.HAND_WRAPS, "gloves/paints/handwrap_leathery_ducttape.vcompmat_c"),
			new Skin("handwrap_leathery_fabric_blue_skulls", GloveItem.Kind.HAND_WRAPS, "gloves/paints/handwrap_leathery_fabric_blue_skulls.vcompmat_c"),
			new Skin("handwrap_leathery_fabric_geometric_blue", GloveItem.Kind.HAND_WRAPS, "gloves/paints/handwrap_leathery_fabric_geometric_blue.vcompmat_c"),
			new Skin("handwrap_leathery_fabric_giraffe", GloveItem.Kind.HAND_WRAPS, "gloves/paints/handwrap_leathery_fabric_giraffe.vcompmat_c"),
			new Skin("handwrap_leathery_fabric_green_camo", GloveItem.Kind.HAND_WRAPS, "gloves/paints/handwrap_leathery_fabric_green_camo.vcompmat_c"),
			new Skin("handwrap_leathery_snakeskin_orange", GloveItem.Kind.HAND_WRAPS, "gloves/paints/handwrap_leathery_snakeskin_orange.vcompmat_c"),
			new Skin("handwrap_red_slaughter", GloveItem.Kind.HAND_WRAPS, "gloves/paints/handwrap_red_slaughter.vcompmat_c"),
			new Skin("motorcycle_basic_black", GloveItem.Kind.MOTO, "gloves/paints/motorcycle_basic_black.vcompmat_c"),
			new Skin("motorcycle_basic_green_orange", GloveItem.Kind.MOTO, "gloves/paints/motorcycle_basic_green_orange.vcompmat_c"),
			new Skin("motorcycle_carbonfiber_red", GloveItem.Kind.MOTO, "gloves/paints/motorcycle_carbonfiber_red.vcompmat_c"),
			new Skin("motorcycle_checker_flag_blue_green", GloveItem.Kind.MOTO, "gloves/paints/motorcycle_checker_flag_blue_green.vcompmat_c"),
			new Skin("motorcycle_choco_boom", GloveItem.Kind.MOTO, "gloves/paints/motorcycle_choco_boom.vcompmat_c"),
			new Skin("motorcycle_commando_ksk", GloveItem.Kind.MOTO, "gloves/paints/motorcycle_commando_ksk.vcompmat_c"),
			new Skin("motorcycle_mint_triangle", GloveItem.Kind.MOTO, "gloves/paints/motorcycle_mint_triangle.vcompmat_c"),
			new Skin("motorcycle_mono_boom", GloveItem.Kind.MOTO, "gloves/paints/motorcycle_mono_boom.vcompmat_c"),
			new Skin("motorcycle_smoke", GloveItem.Kind.MOTO, "gloves/paints/motorcycle_smoke.vcompmat_c"),
			new Skin("motorcycle_triangle_blue", GloveItem.Kind.MOTO, "gloves/paints/motorcycle_triangle_blue.vcompmat_c"),
			new Skin("motorcycle_trigrid_blue", GloveItem.Kind.MOTO, "gloves/paints/motorcycle_trigrid_blue.vcompmat_c"),
			new Skin("motorcycle_yellow_camo", GloveItem.Kind.MOTO, "gloves/paints/motorcycle_yellow_camo.vcompmat_c"),
			new Skin("glove_specialist_abstract_green", GloveItem.Kind.SPECIALIST, "gloves/paints/volatile_02/glove_specialist_abstract_green.vcompmat_c"),
			new Skin("glove_specialist_dragon_blue", GloveItem.Kind.SPECIALIST, "gloves/paints/volatile_02/glove_specialist_dragon_blue.vcompmat_c"),
			new Skin("glove_specialist_graffiti_black", GloveItem.Kind.SPECIALIST, "gloves/paints/volatile_02/glove_specialist_graffiti_black.vcompmat_c"),
			new Skin("glove_specialist_quilted_black", GloveItem.Kind.SPECIALIST, "gloves/paints/volatile_02/glove_specialist_quilted_black.vcompmat_c"),
			new Skin("glove_specialist_quilted_white", GloveItem.Kind.SPECIALIST, "gloves/paints/volatile_02/glove_specialist_quilted_white.vcompmat_c"),
			new Skin("glove_specialist_sunburst_red", GloveItem.Kind.SPECIALIST, "gloves/paints/volatile_02/glove_specialist_sunburst_red.vcompmat_c"),
			new Skin("glove_specialist_wave_blue", GloveItem.Kind.SPECIALIST, "gloves/paints/volatile_02/glove_specialist_wave_blue.vcompmat_c"),
			new Skin("specialist_ddpat_green_camo", GloveItem.Kind.SPECIALIST, "gloves/paints/specialist_ddpat_green_camo.vcompmat_c"),
			new Skin("specialist_emerald_web", GloveItem.Kind.SPECIALIST, "gloves/paints/specialist_emerald_web.vcompmat_c"),
			new Skin("specialist_fade", GloveItem.Kind.SPECIALIST, "gloves/paints/specialist_fade.vcompmat_c"),
			new Skin("specialist_fbi", GloveItem.Kind.SPECIALIST, "gloves/paints/specialist_fbi.vcompmat_c"),
			new Skin("specialist_forest_brown", GloveItem.Kind.SPECIALIST, "gloves/paints/specialist_forest_brown.vcompmat_c"),
			new Skin("specialist_kimono_diamonds_red", GloveItem.Kind.SPECIALIST, "gloves/paints/specialist_kimono_diamonds_red.vcompmat_c"),
			new Skin("specialist_marble_fade", GloveItem.Kind.SPECIALIST, "gloves/paints/specialist_marble_fade.vcompmat_c"),
			new Skin("specialist_orange_white", GloveItem.Kind.SPECIALIST, "gloves/paints/specialist_orange_white.vcompmat_c"),
			new Skin("specialist_ricksaw_camo", GloveItem.Kind.SPECIALIST, "gloves/paints/specialist_ricksaw_camo.vcompmat_c"),
			new Skin("specialist_tiger_orange", GloveItem.Kind.SPECIALIST, "gloves/paints/specialist_tiger_orange.vcompmat_c"),
			new Skin("specialist_webs_red", GloveItem.Kind.SPECIALIST, "gloves/paints/specialist_webs_red.vcompmat_c"),
			new Skin("specialist_winterhex", GloveItem.Kind.SPECIALIST, "gloves/paints/specialist_winterhex.vcompmat_c"),
			new Skin("bloodhound_hydra_black_green", GloveItem.Kind.HYDRA, "gloves/paints/bloodhound_hydra_black_green.vcompmat_c"),
			new Skin("bloodhound_hydra_case_hardened", GloveItem.Kind.HYDRA, "gloves/paints/bloodhound_hydra_case_hardened.vcompmat_c"),
			new Skin("bloodhound_hydra_green_leather_mesh_brass", GloveItem.Kind.HYDRA, "gloves/paints/bloodhound_hydra_green_leather_mesh_brass.vcompmat_c"),
			new Skin("bloodhound_hydra_snakeskin_brass", GloveItem.Kind.HYDRA, "gloves/paints/bloodhound_hydra_snakeskin_brass.vcompmat_c"),
			new Skin("operation10_floral", GloveItem.Kind.BROKEN_FANG, "gloves/paints/operation10_floral.vcompmat_c"),
			new Skin("operation10_metalic_green", GloveItem.Kind.BROKEN_FANG, "gloves/paints/operation10_metalic_green.vcompmat_c"),
			new Skin("operation10_poison_frog_black_yellow", GloveItem.Kind.BROKEN_FANG, "gloves/paints/operation10_poison_frog_black_yellow.vcompmat_c"),
			new Skin("operation10_snakeskin_black", GloveItem.Kind.BROKEN_FANG, "gloves/paints/operation10_snakeskin_black.vcompmat_c"));

	private static final Map<String, Skin> BY_KIT = new LinkedHashMap<>();

	static {
		for (Skin s : ALL) BY_KIT.put(s.kit, s);
	}

	private GloveSkins() {}

	public static Skin get(String kit) {
		return kit == null ? null : BY_KIT.get(kit);
	}

	public static List<Skin> of(GloveItem.Kind kind) {
		List<Skin> out = new ArrayList<>();
		for (Skin s : ALL) if (s.kind == kind) out.add(s);
		return out;
	}

	/** A readable name from the kit's id, for when the CS2 localization isn't converted (yet). */
	public static String fallbackName(String kit) {
		String s = kit.replaceFirst("^(glove_(sport|driver|specialist)_|bloodhound_hydra_|bloodhound_|handwrap_leathery_fabric_|handwrap_leathery_|handwrap_fabric_|handwrap_"
				+ "|motorcycle_|slick_|specialist_|sporty_|operation10_)", "").replace('_', ' ');
		return s.isEmpty() ? kit : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
