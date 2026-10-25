package dev.strafemc.data;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CS2's chat wheel (strafemc/radio.tsv, from panorama's settingsmenu_chatwheel and the agents' talker rules): every
 * phrase's radio name (CW.NeedDrop), its text token (#Chatwheel_requestweapon) and icon, each voice's lines for it, and
 * the voice each agent speaks with. The three wheels' slots are CS2's cl_radial_radio_tab_N_text_1-8 defaults, slot 1
 * at the top and then clockwise.
 */
public final class RadioTable {
	public record Phrase(String radio, String text, String icon) {}

	/** cl_radial_radio_tab_0/1/2_text_1-8. */
	public static final String[][] WHEELS = {
			{"#Chatwheel_quiet", "#Chatwheel_requestecoround", "#Chatwheel_bplan", "#Chatwheel_requestweapon", "#Chatwheel_midplan",
					"#Chatwheel_droppedbomb", "#Chatwheel_aplan", "#Chatwheel_requestspend"},
			{"#Chatwheel_bombcarrierspotted", "#Chatwheel_requestecoround", "#Chatwheel_multipleenemieshere", "#Chatwheel_requestweapon",
					"#Chatwheel_rotatetome", "#Chatwheel_ihavethebomb", "#Chatwheel_oneenemyhere", "#Chatwheel_requestspend"},
			{"#Chatwheel_bombcarrierspotted", "#Chatwheel_requestecoround", "#Chatwheel_multipleenemieshere", "#Chatwheel_requestweapon",
					"#Chatwheel_rotatetome", "#Chatwheel_ihavethebomb", "#Chatwheel_oneenemyhere", "#Chatwheel_requestspend"}};

	private static final Map<String, Phrase> BY_RADIO = new LinkedHashMap<>(), BY_TEXT = new HashMap<>();
	private static final Map<String, List<String>> LINES = new HashMap<>();
	private static final Map<String, String> AGENT_VOICE = new HashMap<>();

	static {
		var in = RadioTable.class.getResourceAsStream("/strafemc/radio.tsv");
		if (in != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] f = line.split("\t", -1);
				switch (f[0]) {
					case "P" -> {
						Phrase p = new Phrase(f[1], f[2], f[3]);
						BY_RADIO.putIfAbsent(p.radio(), p);
						BY_TEXT.putIfAbsent(p.text(), p); // two phrases share "bomb carrier spotted": the panorama list's first wins
					}
					case "V" -> LINES.put(f[1] + "|" + f[2], List.of(f[3].split(",")));
					case "A" -> AGENT_VOICE.put(f[1], f[2]);
					default -> {}
				}
			}
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.error("[strafemc] chat wheel table unreadable", e);
		}
	}

	private RadioTable() {}

	public static Phrase phrase(String radio) {
		return BY_RADIO.get(radio);
	}

	public static Phrase byText(String token) {
		return BY_TEXT.get(token);
	}

	public static List<Phrase> all() {
		return new ArrayList<>(BY_RADIO.values());
	}

	/** The voice's lines for a phrase (sound events, one picked at random), or empty when that voice has none for it. */
	public static List<String> lines(String voice, String radio) {
		return LINES.getOrDefault(voice + "|" + radio, List.of());
	}

	/** The voice an agent speaks with; no agent (or an unknown one): the side's default agent's, phoenix / sas. */
	public static String voice(String agentId, boolean t) {
		String v = agentId == null ? null : AGENT_VOICE.get(agentId);
		return v != null ? v : t ? "phoenix" : "sas";
	}
}
