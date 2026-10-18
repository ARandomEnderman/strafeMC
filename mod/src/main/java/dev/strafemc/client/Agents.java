package dev.strafemc.client;

import dev.strafemc.config.StrafeConfig;
import dev.strafemc.weapon.SkinWraps;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CS2's agents (strafemc/agents.tsv): one per side picked in the strafeMC settings (not items). Your viewmodel wears the
 * picked agent of your side's sleeves (their model's firstperson_sleeves mesh, on the same skeleton as the arms).
 */
public final class Agents {
	public record Agent(String id, String name, String side, String model, String image, SkinWraps.Rarity rarity) {}

	private static final Map<String, Agent> ALL = new LinkedHashMap<>();

	static {
		var in = Agents.class.getResourceAsStream("/strafemc/agents.tsv");
		if (in != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] f = line.split("\t", -1);
				if (f.length < 6) continue;
				SkinWraps.Rarity rar = SkinWraps.Rarity.of(f[5]);
				ALL.put(f[0], new Agent(f[0], f[1].replaceAll("\\s+\\|", " |"), f[2], f[3], f[4], rar == null ? SkinWraps.Rarity.RARE : rar));
			}
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.error("[strafemc] agents unreadable", e);
		}
	}

	private Agents() {}

	public static Agent get(String id) {
		return id == null || id.isEmpty() ? null : ALL.get(id);
	}

	public static List<Agent> of(String side) {
		List<Agent> out = new ArrayList<>();
		for (Agent a : ALL.values()) if (a.side().equals(side)) out.add(a);
		return out;
	}

	/** The agent picked for your current side, or null for none (just the gloves, as before). */
	public static Agent current() {
		return get("t".equals(StrafeConfig.team) ? StrafeConfig.agentT : StrafeConfig.agentCT);
	}
}
