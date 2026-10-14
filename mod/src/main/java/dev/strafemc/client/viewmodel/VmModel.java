package dev.strafemc.client.viewmodel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.strafemc.StrafeMc;
import org.joml.Matrix4f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A skinned CS2 mesh (VRF glTF export): triangles with up to 4 bone weights, its joints by name and their inverse bind matrices. */
public final class VmModel {
	public static final class Part {
		public float[] pos, nrm, uv, wts;
		/** The second UV set (TEXCOORD_1), or null: CS2 guns lay their skins out in it (the old CS:GO layout). */
		public float[] uv1;
		public int[] jnt, idx;
		public Path texture; // base color image next to the .glb
		/** Per vertex: how metallic the texture is there (CS2's metalness mask at the vertex UV), or null if not metal. */
		public float[] metal;
		/** Texture U shift applied when drawing (the Zeus charge meter's display; 0 for everything else). */
		public float uOffset;
		/** glTF alphaMode: OPAQUE (CS2 packs paint/metal masks into alpha, so it must be ignored), MASK or BLEND (decals). */
		public String alphaMode = "OPAQUE";
		public int skin; // index into skins: the skin of this mesh's own node
	}

	/** One glTF skin: its joints by name and their inverse bind matrices. */
	public static final class Skin {
		public String[] joints;
		public Matrix4f[] inverseBind;
		/** Each joint's parent joint in this skin (-1: none), for joints the clips don't animate (cloth, the molotov rag). */
		public int[] parent;
	}

	public final List<Part> parts = new ArrayList<>();
	/** VRF exports several skins per model (e.g. world model and viewmodel); each mesh binds to the one its node names. */
	public final List<Skin> skins = new ArrayList<>();

	/** meshFilter: only meshes whose node name contains this (e.g. ".viewmodel", ".body_hd"); null = all. */
	/** Mesh filter: the model's ".body_hd" mesh if it has one, else ".body_legacy", else ".body" (CS2 weapon models). */
	public static final String AUTO_MESH = "auto";

	public static VmModel load(Glb g, String meshFilter) {
		VmModel m = new VmModel();
		var nodes = g.arr("nodes");
		if (AUTO_MESH.equals(meshFilter)) {
			meshFilter = null;
			for (String want : new String[] {".body_hd", ".body_legacy", ".body"}) {
				for (JsonElement ne : nodes) {
					JsonObject n = ne.getAsJsonObject();
					if (n.has("mesh") && n.has("name") && n.get("name").getAsString().endsWith(want)) meshFilter = want;
				}
				if (meshFilter != null) break;
			}
		}
		for (JsonElement se : g.arr("skins")) {
			JsonObject skin = se.getAsJsonObject();
			var js = skin.getAsJsonArray("joints");
			Skin s = new Skin();
			s.joints = new String[js.size()];
			for (int i = 0; i < js.size(); i++) s.joints[i] = nodes.get(js.get(i).getAsInt()).getAsJsonObject().get("name").getAsString();
			s.inverseBind = new Matrix4f[js.size()];
			float[] ibm = skin.has("inverseBindMatrices") ? g.floats(skin.get("inverseBindMatrices").getAsInt()) : null;
			for (int i = 0; i < js.size(); i++) s.inverseBind[i] = ibm == null ? new Matrix4f() : new Matrix4f().set(ibm, i * 16);
			java.util.Map<Integer, Integer> jointOf = new java.util.HashMap<>();
			for (int i = 0; i < js.size(); i++) jointOf.put(js.get(i).getAsInt(), i);
			s.parent = new int[js.size()];
			java.util.Arrays.fill(s.parent, -1);
			for (int ni = 0; ni < nodes.size(); ni++) {
				JsonObject pn = nodes.get(ni).getAsJsonObject();
				Integer pj = jointOf.get(ni);
				if (pj == null || !pn.has("children")) continue;
				for (JsonElement ch : pn.getAsJsonArray("children")) {
					Integer cj = jointOf.get(ch.getAsInt());
					if (cj != null) s.parent[cj] = pj;
				}
			}
			m.skins.add(s);
		}
		for (JsonElement ne : nodes) {
			JsonObject n = ne.getAsJsonObject();
			if (!n.has("mesh")) continue;
			String name = n.has("name") ? n.get("name").getAsString() : "";
			if (meshFilter != null && !name.contains(meshFilter)) continue;
			JsonObject mesh = g.obj("meshes", n.get("mesh").getAsInt());
			for (JsonElement pe : mesh.getAsJsonArray("primitives")) {
				JsonObject p = pe.getAsJsonObject(), at = p.getAsJsonObject("attributes");
				if (!at.has("POSITION") || !p.has("indices")) continue;
				Part part = new Part();
				part.skin = n.has("skin") ? n.get("skin").getAsInt() : -1;
				part.pos = g.floats(at.get("POSITION").getAsInt());
				part.nrm = at.has("NORMAL") ? g.floats(at.get("NORMAL").getAsInt()) : new float[part.pos.length];
				part.uv = at.has("TEXCOORD_0") ? g.floats(at.get("TEXCOORD_0").getAsInt()) : new float[part.pos.length / 3 * 2];
				part.uv1 = at.has("TEXCOORD_1") ? g.floats(at.get("TEXCOORD_1").getAsInt()) : null;
				part.jnt = at.has("JOINTS_0") ? g.ints(at.get("JOINTS_0").getAsInt()) : null;
				part.wts = at.has("WEIGHTS_0") ? g.floats(at.get("WEIGHTS_0").getAsInt()) : null;
				part.idx = g.ints(p.get("indices").getAsInt());
				part.texture = baseColor(g, p);
				if (p.has("material")) {
					JsonObject mat = g.obj("materials", p.get("material").getAsInt());
					if (mat.has("alphaMode")) part.alphaMode = mat.get("alphaMode").getAsString();
				}
				int nv = part.pos.length / 3;
				for (int ix : part.idx) if (ix < 0 || ix >= nv) throw new IllegalStateException("bad index " + ix + " (" + nv + " verts) in " + g.file);
				if (part.jnt != null && part.skin >= 0) {
					int nj = m.skins.get(part.skin).joints.length;
					for (int jx : part.jnt) if (jx < 0 || jx >= nj) throw new IllegalStateException("bad joint " + jx + " (" + nj + " joints) in " + g.file);
				}
				m.parts.add(part);
			}
		}
		StrafeMc.LOG.info("[strafemc] viewmodel mesh {}: {} parts, {} skins", g.file.getFileName(), m.parts.size(), m.skins.size());
		return m;
	}

	private static Path baseColor(Glb g, JsonObject prim) {
		try {
			if (!prim.has("material")) return null;
			JsonObject mat = g.obj("materials", prim.get("material").getAsInt());
			JsonObject pbr = mat.getAsJsonObject("pbrMetallicRoughness");
			if (pbr == null || !pbr.has("baseColorTexture")) return null;
			int tex = pbr.getAsJsonObject("baseColorTexture").get("index").getAsInt();
			int img = g.obj("textures", tex).get("source").getAsInt();
			JsonObject im = g.obj("images", img);
			return im.has("uri") ? g.file.getParent().resolve(im.get("uri").getAsString()) : null;
		} catch (Exception e) {
			return null;
		}
	}
}
