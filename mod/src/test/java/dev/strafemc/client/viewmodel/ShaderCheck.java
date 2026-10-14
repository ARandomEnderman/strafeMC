package dev.strafemc.client.viewmodel;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;

/** Compiles the viewmodel shaders in a hidden OpenGL window (Minecraft's #moj_import includes resolved) and prints any errors. */
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
		for (String[] s : new String[][] {{"viewmodel.vsh", "" + GL20.GL_VERTEX_SHADER}, {"viewmodel.fsh", "" + GL20.GL_FRAGMENT_SHADER}}) {
			String src = resolve(read("/assets/strafemc/shaders/core/" + s[0]), new java.util.HashSet<>());
			int sh = GL20.glCreateShader(Integer.parseInt(s[1]));
			GL20.glShaderSource(sh, src);
			GL20.glCompileShader(sh);
			boolean ok = GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) != 0;
			System.out.println(s[0] + (ok ? " OK" : " FAILED\n" + GL20.glGetShaderInfoLog(sh)));
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

	/** #moj_import <ns:file> -> assets/ns/shaders/include/file, once each. */
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
