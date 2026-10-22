package dev.strafemc.client.viewmodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * CS2's end of match scene, played in the world as CS2 plays it in the map: the line-up stands where the map's
 * end_of_match spot is (the server's: a spot set by an operator, else just in front of you, facing you) and the camera
 * runs the prefab's 5 s path to it, CS2's 60 deg horizontal field of view, the HUD off. Agents (when agent models are
 * on) or Minecraft player models in their own skins (EomLineup) play the end of match clips.
 */
public final class EomScene {
	private static boolean active, hidGui;
	private static long startNs;
	private static Vec3 origin = Vec3.ZERO;
	private static float yaw;
	private static List<EomLineup.Member> line = List.of();
	private static List<String[]> plays = List.of();

	private EomScene() {}

	public static boolean active() {
		return active;
	}

	/** Starts the scene from the mode's state ("eom": the line, "eomSpot": the prefab's place and facing). */
	public static void start(JsonObject s, boolean won1, boolean ffa) {
		JsonObject spot = s.getAsJsonObject("eomSpot");
		origin = new Vec3(spot.get("x").getAsDouble(), spot.get("y").getAsDouble(), spot.get("z").getAsDouble());
		yaw = spot.get("yaw").getAsFloat();
		line = members(s);
		plays = EomLineup.plays(line);
		startNs = System.nanoTime();
		var mc = Minecraft.getInstance();
		if (!active) {
			hidGui = mc.options.hideGui;
			mc.options.hideGui = true;
		}
		active = true;
	}

	public static void stop() {
		if (!active) return;
		active = false;
		Minecraft.getInstance().options.hideGui = hidGui;
	}

	/** The members from the state: agent model (the one they picked, else their side's), entity, name, side, won, weapon. */
	public static List<EomLineup.Member> members(JsonObject s) {
		List<EomLineup.Member> out = new ArrayList<>();
		if (!s.has("eom")) return out;
		JsonArray a = s.getAsJsonArray("eom");
		String w = s.has("winner") ? s.get("winner").getAsString() : "";
		boolean ffa = s.has("ffa") && s.get("ffa").getAsBoolean();
		int winner = ffa ? 0 : w.startsWith("Terrorists") ? 1 : w.startsWith("Counter") ? 2 : 0;
		for (int i = 0; i < a.size(); i++) {
			JsonObject x = a.get(i).getAsJsonObject();
			boolean t = x.get("t").getAsBoolean();
			dev.strafemc.client.Agents.Agent ag = dev.strafemc.client.Agents.get(x.get("agent").getAsString());
			String model = ag != null ? ag.model() : t ? AgentModels.T_DEFAULT : AgentModels.CT_DEFAULT;
			boolean won = winner == 0 || (winner == 1) == t; // free for all and a tie: everyone cheers
			out.add(new EomLineup.Member(model, x.get("id").getAsInt(), x.get("name").getAsString(), t, won, x.get("w").getAsString()));
		}
		return out;
	}

	public static double seconds() {
		return (System.nanoTime() - startNs) / 1e9;
	}

	/** The camera now: {position, yaw}. */
	public static Object[] camera() {
		return EomLineup.camera(origin, yaw, seconds());
	}

	/** CS2's 60 deg horizontal field of view as Minecraft's vertical one for this window. */
	public static float fov() {
		var w = Minecraft.getInstance().getWindow();
		double aspect = (double) w.getWidth() / Math.max(1, w.getHeight());
		return (float) Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(30)) / aspect));
	}

	/** Every frame while it runs: the line-up into the world. */
	public static void render(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext ctx) {
		if (!active) return;
		var mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		double sec = seconds();
		for (int i = 0; i < line.size(); i++) {
			Object[] pl = EomLineup.place(origin, yaw, i, line.size());
			Vec3 at = (Vec3) pl[0];
			int light = net.minecraft.client.renderer.LevelRenderer.getLightCoords(mc.level, BlockPos.containing(at.add(0, 1, 0)));
			EomLineup.Member m = line.get(i);
			boolean drew = StrafeConfig.agentModels && EomLineup.submitAgent(m, plays.get(i), sec, at, (float) pl[1], cam, ctx.poseStack(), ctx.submitNodeCollector(), light);
			if (!drew) EomLineup.submitMinecraft(m, plays.get(i), sec, at, (float) pl[1], cam, ctx.poseStack(), ctx.submitNodeCollector(), light);
		}
	}
}
