package dev.strafemc.data;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inspect variants of a weapon from its CS2 inspect graph (animation/graphs/viewmodel/viewmodel_inspects.vnmgraph+<name>.vnmgraph):
 * the first CNmParameterizedClipSelectorNode picks the inspect clip from its options by m_optionWeights (driven by the
 * "inspect_variation" parameter, assumed uniform random - FLAGGED); options without a clip (m_nDataSlotIdx -1) are ignored
 * (m_bIgnoreInvalidOptions). Data slots index the graph's resource list. E.g. Falchion: lookat01 x2, lookat02 x1.
 */
public final class InspectGraph {
	/** clip name (e.g. "lookat02") and weight. */
	public record Option(String anim, double weight) {}

	private static final Map<String, List<Option>> CACHE = new ConcurrentHashMap<>();
	private static final Pattern RES = Pattern.compile("resource:\"[^\"]*/([a-z0-9_]+?)_[a-z0-9]+\\.vnmclip\""),
			SELECTOR = Pattern.compile("CNmParameterizedClipSelectorNode::CDefinition\"\\s*m_nNodeIdx = \\d+\\s*m_optionNodeIndices = \\[([^\\]]*)\\]\\s*m_optionWeights = \\[([^\\]]*)\\]");

	private InspectGraph() {}

	/** Weighted inspect options for a graph suffix like "knife_falchion"; empty if the weapon has no inspect graph. */
	public static List<Option> options(String graphName) {
		return CACHE.computeIfAbsent(graphName, InspectGraph::load);
	}

	private static List<Option> load(String graphName) {
		List<Option> out = new ArrayList<>();
		try {
			String text = Files.readString(AssetCache.block("animation/graphs/viewmodel/viewmodel_inspects.vnmgraph+" + graphName + ".vnmgraph_c", "DATA"));
			int resAt = text.indexOf("m_resources");
			List<String> clips = new ArrayList<>();
			Matcher rm = RES.matcher(resAt < 0 ? text : text.substring(resAt));
			while (rm.find()) clips.add(rm.group(1));
			Matcher sm = SELECTOR.matcher(text);
			if (!sm.find()) return out;
			String[] nodes = sm.group(1).trim().split("\\s*,\\s*"), weights = sm.group(2).trim().split("\\s*,\\s*");
			for (int i = 0; i < nodes.length; i++) {
				Matcher nm = Pattern.compile("m_nNodeIdx = " + nodes[i].trim() + "\\s*m_nPlayInReverseValueNodeIdx = -?\\d+\\s*m_bSampleRootMotion = \\w+\\s*m_bAllowLooping = \\w+\\s*m_nDataSlotIdx = (-?\\d+)").matcher(text);
				if (!nm.find()) continue;
				int slot = Integer.parseInt(nm.group(1));
				if (slot < 0 || slot >= clips.size()) continue; // option without a clip: ignored
				out.add(new Option(clips.get(slot), Double.parseDouble(weights[i].trim())));
			}
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.warn("[strafemc] inspect graph {} unavailable: {}", graphName, e.toString());
		}
		return out;
	}

	private static final java.util.Set<String> QUEUED = ConcurrentHashMap.newKeySet();
	private static final java.util.concurrent.ExecutorService LOADER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "strafemc-inspect-graphs");
		t.setDaemon(true);
		return t;
	});

	/** The options if they're read already; else null, and they're read in the background (never blocks the game). */
	public static List<Option> peek(String graphName) {
		List<Option> o = CACHE.get(graphName);
		if (o == null && QUEUED.add(graphName)) LOADER.execute(() -> options(graphName));
		return o;
	}

	/** Weighted random pick; fallback when the graph gave nothing or isn't read yet (non-blocking: see peek). */
	public static String pick(String graphName, java.util.Random rng, String fallback) {
		List<Option> o = peek(graphName);
		if (o == null) return fallback;
		double total = 0;
		for (Option x : o) total += x.weight();
		if (total <= 0) return fallback;
		double r = rng.nextDouble() * total;
		for (Option x : o) if ((r -= x.weight()) < 0) return x.anim();
		return o.get(o.size() - 1).anim();
	}
}
