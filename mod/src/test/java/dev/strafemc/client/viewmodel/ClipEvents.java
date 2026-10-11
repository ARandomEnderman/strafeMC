package dev.strafemc.client.viewmodel;

import dev.strafemc.data.AnimClip;

public final class ClipEvents {
	public static void main(String[] a) {
		for (String c : new String[] {"reload", "draw", "shoot1"}) {
			AnimClip clip = AnimClip.load("animation/anims/viewmodel/rifle/rifle_ak/" + c + "_ak.vnmclip_c");
			System.out.println(c + " duration " + clip.duration());
			for (AnimClip.Event e : clip.events()) System.out.println("   " + e);
		}
	}
}
