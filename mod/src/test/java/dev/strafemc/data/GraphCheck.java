package dev.strafemc.data;

/** Dumps the DATA block of animation graphs (e.g. a knife's inspect graph) into the cache and prints the path. */
public final class GraphCheck {
	public static void main(String[] a) throws Exception {
		for (String g : a) System.out.println(g + " -> " + AssetCache.block(g, "DATA"));
	}
}
