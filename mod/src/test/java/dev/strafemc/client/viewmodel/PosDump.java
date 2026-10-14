package dev.strafemc.client.viewmodel;

import dev.strafemc.data.AssetCache;

/** Writes our mesh-rasterised positions (1024^2 x 3 floats, little endian; NaN = empty) for comparing with CS2's g_tPosition. */
public final class PosDump {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		var e = dev.strafemc.weapon.WeaponTable.get(a[1]);
		VmModel m = VmModel.load(Glb.read(AssetCache.exportGlb(e.model(), true)), VmModel.AUTO_MESH);
		var pm = WeaponSkinCache.paintable(e.model(), m.parts);
		var parts = new java.util.ArrayList<VmModel.Part>();
		for (VmModel.Part p : m.parts) if (p.texture != null && p.texture.getFileName().toString().startsWith(pm.color())) parts.add(p);
		var pos = WeaponSkinBaker.positions(parts, 1024);
		var buf = java.nio.ByteBuffer.allocate(1024 * 1024 * 12).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < 1024 * 1024; i++)
			for (int k = 0; k < 3; k++) buf.putFloat(pos.pos()[i * WeaponSkinBaker.Positions.STRIDE + k]);
		java.nio.file.Files.write(java.nio.file.Path.of(a[0]), buf.array());
	}
}
