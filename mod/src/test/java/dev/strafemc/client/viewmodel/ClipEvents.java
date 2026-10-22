package dev.strafemc.client.viewmodel;

import dev.strafemc.data.AnimClip;

public final class ClipEvents {
	/** Args: clip paths (default: the AK's reload, draw and shoot1). */
	public static void main(String[] a) {
		String[] paths = a.length > 0 ? a : new String[] {"animation/anims/viewmodel/rifle/rifle_ak/reload_ak.vnmclip_c",
				"animation/anims/viewmodel/rifle/rifle_ak/draw_ak.vnmclip_c", "animation/anims/viewmodel/rifle/rifle_ak/shoot1_ak.vnmclip_c"};
		for (String c : paths) {
			AnimClip clip = AnimClip.load(c);
			System.out.println(c + " duration " + clip.duration());
			for (AnimClip.Event e : clip.events()) System.out.println("   " + e);
		}
	}
}
