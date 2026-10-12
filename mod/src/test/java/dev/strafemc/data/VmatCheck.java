package dev.strafemc.data;

/** Prints decompiled resources (e.g. decal vmats). */
public final class VmatCheck {
	public static void main(String[] a) throws Exception {
		for (String p : a) System.out.println("== " + p + "\n" + java.nio.file.Files.readString(AssetCache.decompiled(p)));
	}
}
