package dev.strafemc.client.viewmodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal binary glTF (.glb) reader for the files ValveResourceFormat exports: JSON + one BIN chunk, typed accessors. */
public final class Glb {
	public final JsonObject json;
	private final ByteBuffer bin;
	public final Path file;

	private Glb(Path file, JsonObject json, ByteBuffer bin) {
		this.file = file;
		this.json = json;
		this.bin = bin;
	}

	public static Glb read(Path file) throws Exception {
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt(0) != 0x46546C67) throw new IllegalArgumentException("not a GLB: " + file);
		int jsonLen = b.getInt(12);
		String js = new String(b.array(), 20, jsonLen, StandardCharsets.UTF_8);
		int binAt = 20 + jsonLen;
		ByteBuffer bin = null;
		if (binAt + 8 <= b.limit()) {
			int binLen = b.getInt(binAt);
			bin = ByteBuffer.wrap(b.array(), binAt + 8, binLen).slice().order(ByteOrder.LITTLE_ENDIAN);
		}
		return new Glb(file, JsonParser.parseString(js).getAsJsonObject(), bin);
	}

	public JsonArray arr(String key) {
		return json.has(key) ? json.getAsJsonArray(key) : new JsonArray();
	}

	public JsonObject obj(String key, int i) {
		return arr(key).get(i).getAsJsonObject();
	}

	private static int comps(String type) {
		return switch (type) {
			case "SCALAR" -> 1;
			case "VEC2" -> 2;
			case "VEC3" -> 3;
			case "VEC4" -> 4;
			case "MAT4" -> 16;
			default -> throw new IllegalArgumentException(type);
		};
	}

	/** Any accessor as floats (normalized integer types are scaled to 0..1). */
	public float[] floats(int accessor) {
		JsonObject a = obj("accessors", accessor);
		int n = comps(a.get("type").getAsString()), count = a.get("count").getAsInt(), ct = a.get("componentType").getAsInt();
		boolean norm = a.has("normalized") && a.get("normalized").getAsBoolean();
		JsonObject bv = obj("bufferViews", a.get("bufferView").getAsInt());
		int base = (bv.has("byteOffset") ? bv.get("byteOffset").getAsInt() : 0) + (a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0);
		int size = switch (ct) {
			case 5126, 5125 -> 4; // float, unsigned int (index buffers)
			case 5123, 5122 -> 2;
			default -> 1;
		};
		int stride = bv.has("byteStride") ? bv.get("byteStride").getAsInt() : n * size;
		float[] out = new float[count * n];
		for (int i = 0; i < count; i++)
			for (int k = 0; k < n; k++) {
				int at = base + i * stride + k * size;
				float v = switch (ct) {
					case 5126 -> bin.getFloat(at);
					case 5123 -> norm ? (bin.getShort(at) & 0xFFFF) / 65535f : bin.getShort(at) & 0xFFFF;
					case 5122 -> bin.getShort(at);
					case 5121 -> norm ? (bin.get(at) & 0xFF) / 255f : bin.get(at) & 0xFF;
					case 5125 -> bin.getInt(at);
					default -> bin.get(at);
				};
				out[i * n + k] = v;
			}
		return out;
	}

	/** Index / joint accessors as ints. */
	public int[] ints(int accessor) {
		float[] f = floats(accessor);
		int[] o = new int[f.length];
		for (int i = 0; i < f.length; i++) o[i] = Math.round(f[i]);
		return o;
	}
}
