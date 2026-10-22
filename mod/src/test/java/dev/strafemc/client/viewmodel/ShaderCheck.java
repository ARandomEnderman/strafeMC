package dev.strafemc.client.viewmodel;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;

/**
 * Compiles and links the viewmodel shader programs in a hidden OpenGL window, as the game does (Minecraft's #moj_import
 * includes resolved, the pipeline's defines added), and fails on any error: a broken shader leaves the viewmodel
 * invisible in game without an exception.
 */
public final class ShaderCheck {
	public static void main(String[] a) throws Exception {
		if (!GLFW.glfwInit()) throw new IllegalStateException("no GLFW");
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
		long w = GLFW.glfwCreateWindow(16, 16, "shadercheck", 0, 0);
		GLFW.glfwMakeContextCurrent(w);
		GL.createCapabilities();
		System.out.println("compiling on " + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
		int failed = 0;
		// each program as the game links it: vertex + fragment shader, with the pipeline's defines
		String[][] programs = {{"viewmodel.vsh", "viewmodel.fsh", ""}, {"viewmodel_skinned.vsh", "viewmodel.fsh", "#define MAX_JOINTS " + GpuSkin.MAX_JOINTS},
				{"minecraft:screenquad.vsh", "smoke_volume.fsh", ""}, {"minecraft:screenquad.vsh", "smoke_composite.fsh", ""},
				{"minecraft:screenquad.vsh", "bloom_down.fsh", ""}, {"minecraft:screenquad.vsh", "bloom_down.fsh", "#define FIRST"},
				{"minecraft:screenquad.vsh", "bloom_up.fsh", ""}, {"minecraft:screenquad.vsh", "bloom_composite.fsh", ""}};
		for (String[] prog : programs) {
			int program = GL20.glCreateProgram();
			boolean ok = true;
			for (int k = 0; k < 2; k++) {
				String file = prog[k], ns = "strafemc";
				if (file.startsWith("minecraft:")) {
					ns = "minecraft";
					file = file.substring(10);
				}
				String raw = read("/assets/" + ns + "/shaders/core/" + file);
				if (ns.equals("strafemc") && !lint(file, raw)) ok = false;
				String src = resolve(raw, new java.util.HashSet<>());
				if (!prog[2].isEmpty()) {
					int eol = src.indexOf('\n') + 1; // after #version
					src = src.substring(0, eol) + prog[2] + "\n" + src.substring(eol);
				}
				int sh = GL20.glCreateShader(k == 0 ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
				GL20.glShaderSource(sh, src);
				GL20.glCompileShader(sh);
				if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == 0) {
					System.out.println(prog[k] + " FAILED\n" + GL20.glGetShaderInfoLog(sh));
					ok = false;
				} else if (ns.equals("strafemc") && GL20.glGetShaderInfoLog(sh).toLowerCase(java.util.Locale.ROOT).contains("warning")) {
					// what this driver only warns about, a stricter one (AMD) may refuse
					System.out.println(prog[k] + " has warnings\n" + GL20.glGetShaderInfoLog(sh));
					ok = false;
				}
				GL20.glAttachShader(program, sh);
			}
			if (ok) {
				GL20.glLinkProgram(program);
				ok = GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0;
				if (!ok) System.out.println("link FAILED\n" + GL20.glGetProgramInfoLog(program));
			}
			System.out.println(prog[0] + " + " + prog[1] + (ok ? " OK" : " FAILED"));
			if (!ok) failed++;
		}
		GLFW.glfwDestroyWindow(w);
		GLFW.glfwTerminate();
		if (failed > 0) System.exit(1);
	}

	/** Words some drivers reserve in a #version 330 shader (later GLSL keywords and the spec's reserved list). */
	private static final java.util.Set<String> RESERVED = java.util.Set.of("sample", "patch", "subroutine", "precise", "buffer", "shared",
			"coherent", "restrict", "readonly", "writeonly", "resource", "common", "partition", "active", "asm", "class", "union", "enum",
			"typedef", "template", "this", "packed", "goto", "inline", "noinline", "volatile", "public", "static", "extern", "external",
			"interface", "long", "short", "double", "half", "fixed", "unsigned", "superp", "input", "output", "filter", "sizeof", "cast",
			"namespace", "using", "dvec2", "dvec3", "dvec4", "dmat2", "dmat3", "dmat4", "image2D", "atomic_uint");
	/** Compatibility-profile names: a core profile driver may refuse them (some accept them anyway). */
	private static final java.util.Set<String> OLD = java.util.Set.of("texture2D", "texture2DLod", "texture3D", "textureCube", "shadow2D",
			"gl_FragColor", "gl_FragData", "attribute", "varying", "ftransform", "gl_Vertex", "gl_ModelViewMatrix",
			"gl_ModelViewProjectionMatrix", "gl_TexCoord", "gl_MultiTexCoord0");
	private static final String TYPES = "float|int|uint|bool|[iu]?vec[234]|mat[234]";

	/**
	 * What a strict driver (AMD) refuses or does differently, while the driver this check runs on may accept it: our
	 * functions named strafe... (GLSL keeps old built-ins like noise1..noise4, and reusing one is refused), no reserved
	 * words or compatibility names, #version first, global initializers const, and no local read before it is set
	 * (NVIDIA starts it at zero, AMD leaves garbage). Prints each problem; false if any.
	 */
	private static boolean lint(String file, String raw) {
		java.util.List<String> bad = new java.util.ArrayList<>();
		if (!raw.startsWith("#version")) bad.add("#version must be the very first text (no BOM, comment or blank line before it)");
		// comments out (newlines kept for the line numbers), preprocessor lines blanked
		String src = java.util.regex.Pattern.compile("/\\*.*?\\*/", java.util.regex.Pattern.DOTALL).matcher(raw)
				.replaceAll(r -> r.group().replaceAll("[^\\n]", " "));
		src = src.replaceAll("//[^\\n]*", "").replaceAll("(?m)^\\s*#[^\\n]*", "");
		var fn = java.util.regex.Pattern.compile("(?m)^\\s*(?:void|" + TYPES + ")\\s+([A-Za-z_]\\w*)\\s*\\(").matcher(src);
		while (fn.find())
			if (!fn.group(1).equals("main") && !fn.group(1).startsWith("strafe")) bad.add(line(src, fn.start(1)) + "function " + fn.group(1) + " needs the strafe prefix");
		var word = java.util.regex.Pattern.compile("(?<![\\w.])[A-Za-z_]\\w*").matcher(src);
		while (word.find()) {
			if (RESERVED.contains(word.group())) bad.add(line(src, word.start()) + "\"" + word.group() + "\" is reserved on some drivers");
			if (OLD.contains(word.group())) bad.add(line(src, word.start()) + word.group() + " is compatibility-profile only");
		}
		// a global's starting value must be a constant expression: not a uniform, an input, another non-const global or
		// one of our functions (NVIDIA accepts that, the spec and AMD don't)
		java.util.Set<String> varying = new java.util.HashSet<>();
		java.util.List<int[]> inits = new java.util.ArrayList<>();
		int depth = 0, start = 0;
		for (int i = 0; i < src.length(); i++) {
			char c = src.charAt(i);
			if (c == '{') {
				if (depth++ == 0 && src.substring(start, i).contains("uniform")) // a uniform block: its members aren't constant
					for (var mm = java.util.regex.Pattern.compile("([A-Za-z_]\\w*)\\s*(?:\\[[^\\]]*\\])?\\s*;").matcher(src.substring(i, src.indexOf('}', i))); mm.find(); )
						varying.add(mm.group(1));
			} else if (c == '}' && --depth == 0) start = i + 1;
			else if (c == ';' && depth == 0) {
				String st = src.substring(start, i).trim();
				boolean isConst = st.startsWith("const");
				// every name this statement declares (before its "=" if any, then after each top-level comma)
				for (String part : st.split(",(?![^(]*\\))")) {
					String head = part.split("=")[0].trim();
					String name = head.replaceAll("(?s).*?([A-Za-z_]\\w*)\\s*(\\[[^\\]]*\\])?$", "$1");
					if (!isConst) varying.add(name);
				}
				if (!isConst && !st.startsWith("uniform") && st.matches("(?s)[^(]*[^=!<>]=[^=].*")) inits.add(new int[] {start, i});
				start = i + 1;
			}
		}
		for (int[] r : inits) {
			String st = src.substring(r[0], r[1]);
			for (String part : st.split(",(?![^(]*\\))")) {
				int eq = part.indexOf('=');
				if (eq < 0) continue;
				var id = java.util.regex.Pattern.compile("(?<![\\w.])[A-Za-z_]\\w*").matcher(part.substring(eq + 1));
				while (id.find())
					if (varying.contains(id.group()) || id.group().startsWith("strafe") || id.group().startsWith("gl_"))
						bad.add(line(src, r[0] + st.indexOf(part)) + "global initializer uses " + id.group() + ", which is not constant: " + part.trim().replaceAll("\\s+", " "));
			}
		}
		// a local declared without a value whose first use reads it
		var decl = java.util.regex.Pattern.compile("(?<![\\w.])(?:" + TYPES + ")\\s+([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)*)\\s*;").matcher(src);
		while (decl.find()) {
			int body = functionEnd(src, decl.start());
			if (body < 0) continue; // a global, a block member or an input
			for (String name : decl.group(1).split("\\s*,\\s*")) {
				var use = java.util.regex.Pattern.compile("(?<![\\w.])" + name + "(?!\\w)").matcher(src).region(decl.end(), body);
				if (use.find() && !src.substring(use.end(), body).matches("(?s)\\s*=[^=].*"))
					bad.add(line(src, use.start()) + name + " is used before it is set (AMD leaves it undefined, not zero)");
			}
		}
		for (String b : bad) System.out.println(file + ":" + b);
		return bad.isEmpty();
	}

	/** End of the function body around pos, or -1 if pos is outside a function (top level, or a uniform block / struct). */
	private static int functionEnd(String src, int pos) {
		int depth = 0, open = -1;
		for (int i = 0; i < pos; i++) {
			char c = src.charAt(i);
			if (c == '{' && depth++ == 0) open = i;
			else if (c == '}') depth--;
		}
		if (depth == 0 || src.substring(src.lastIndexOf(';', open) + 1, open).indexOf('(') < 0) return -1;
		for (int i = pos, d = depth; i < src.length(); i++) {
			char c = src.charAt(i);
			if (c == '{') d++;
			else if (c == '}' && --d == 0) return i;
		}
		return src.length();
	}

	private static String line(String src, int pos) {
		return (src.substring(0, pos).split("\n", -1).length) + ": ";
	}

	private static String read(String path) throws Exception {
		try (var in = ShaderCheck.class.getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException("missing " + path);
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
	}

	/** #moj_import <ns:file> -> assets/ns/shaders/include/file, once each, without its own #version. */
	private static String resolve(String src, java.util.Set<String> seen) throws Exception {
		var m = java.util.regex.Pattern.compile("#moj_import <([a-z0-9_]+):([^>]+)>").matcher(src);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String path = "/assets/" + m.group(1) + "/shaders/include/" + m.group(2);
			m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(seen.add(path) ? resolve(read(path), seen).replaceAll("(?m)^#version.*$", "") : ""));
		}
		m.appendTail(out);
		return out.toString();
	}
}
