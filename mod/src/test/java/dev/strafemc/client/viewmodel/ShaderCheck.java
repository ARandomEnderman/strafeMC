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
		int failed = 0;
		// each program as the game links it: vertex + fragment shader, with the pipeline's defines
		String[][] programs = {{"viewmodel.vsh", "viewmodel.fsh", ""}, {"viewmodel_skinned.vsh", "viewmodel.fsh", "#define MAX_JOINTS " + GpuSkin.MAX_JOINTS},
				{"minecraft:screenquad.vsh", "smoke_volume.fsh", ""}, {"minecraft:screenquad.vsh", "smoke_composite.fsh", ""}};
		for (String[] prog : programs) {
			int program = GL20.glCreateProgram();
			boolean ok = true;
			for (int k = 0; k < 2; k++) {
				String file = prog[k], ns = "strafemc";
				if (file.startsWith("minecraft:")) {
					ns = "minecraft";
					file = file.substring(10);
				}
				String src = resolve(read("/assets/" + ns + "/shaders/core/" + file), new java.util.HashSet<>());
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
