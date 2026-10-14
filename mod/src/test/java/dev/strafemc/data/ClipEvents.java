package dev.strafemc.data;

/** Prints a viewmodel clip's duration and events (sounds, inspect loop marks): gradlew vmCheck -PvmMain=dev.strafemc.data.ClipEvents -PvmArgs=<clip path>,... */
public final class ClipEvents {
	public static void main(String[] a) {
		for (String p : a) {
			AnimClip c = AnimClip.load(p);
			System.out.println("== " + p + " duration " + c.duration());
			for (AnimClip.Event e : c.events())
				System.out.printf("  %-34s %6.3f +%6.3f %s%n", e.name(), e.time(), e.duration(), e.isSound() ? "sound" : e.extra());
		}
	}
}
