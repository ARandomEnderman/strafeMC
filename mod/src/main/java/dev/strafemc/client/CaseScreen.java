package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.Cases;
import dev.strafemc.weapon.SkinWraps;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A CS2 weapon case: its contents by tier in CS2's rarity colours, then "Unlock Container": the server rolls, and the
 * reel spins and slows down onto what came out, as in CS2.
 */
public final class CaseScreen extends Screen {
	/** One reel / contents tile: the item to show, its rarity colour and name. */
	private record Tile(ItemStack stack, int color, String name, Cases.Entry entry) {}

	private static final int TILE = 64, GOLD = 0xFFE4AE39;
	private static final double SPIN_SECONDS = 6.5;
	private static CaseScreen open;

	private final Cases.Case c;
	private final List<Tile> contents = new ArrayList<>();
	private Button unlock;
	/** The reel once the result is in, the index it lands on, when it started. */
	private List<Tile> reel;
	private int landing;
	private long spinStart;
	private int lastTick = -1;
	private String result;
	private int resultColor;

	private CaseScreen(Cases.Case c) {
		super(Component.literal(c.name()));
		this.c = c;
		for (int t = SkinWraps.Rarity.values().length - 1; t >= 0; t--) {
			var list = c.guns().get(SkinWraps.Rarity.values()[t]);
			if (list == null) continue;
			for (Cases.Entry e : list) {
				ItemStack s = Cases.stack(e, 0);
				if (!s.isEmpty()) contents.add(new Tile(s, 0xFF000000 | SkinWraps.Rarity.values()[t].color, s.getHoverName().getString(), e));
			}
		}
	}

	public static void open(String caseId) {
		Cases.Case c = Cases.get(caseId);
		if (c != null) { // every gun's picture in one converter run
			List<Cases.Entry> all = new ArrayList<>();
			for (var list : c.guns().values()) all.addAll(list);
			CaseImages.load(all);
		}
		if (c != null) Minecraft.getInstance().setScreen(open = new CaseScreen(c));
	}

	/** The server's roll: build the reel ending on it and spin. */
	public static void result(StrafePayloads.CaseResult r) {
		if (open == null || Minecraft.getInstance().screen != open || !open.c.id().equals(r.caseId())) return;
		open.spin(r);
	}

	@Override
	protected void init() {
		unlock = addRenderableWidget(Button.builder(Component.literal("Unlock Container"), b -> {
			if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("case_open", c.id()));
			b.active = false;
		}).bounds(width / 2 - 75, height - 30, 150, 20).build());
		if (reel != null) unlock.active = false;
	}

	private void spin(StrafePayloads.CaseResult r) {
		Random rng = new Random();
		List<Tile> tiles = new ArrayList<>();
		// filler by the same odds the server rolls with, a gold "rare special" tile now and then
		for (int i = 0; i < 60; i++) tiles.add(filler(rng));
		landing = 52;
		ItemStack got = Cases.stack(new Cases.Entry(r.kit(), r.item()), 0);
		int color = r.rarity() < 0 ? GOLD : 0xFF000000 | SkinWraps.Rarity.values()[r.rarity()].color;
		tiles.set(landing, new Tile(got, color, r.name(), new Cases.Entry(r.kit(), r.item())));
		reel = tiles;
		spinStart = System.nanoTime();
		result = r.name();
		resultColor = color;
	}

	private Tile filler(Random rng) {
		double x = rng.nextDouble();
		SkinWraps.Rarity t = x < 0.7992 ? SkinWraps.Rarity.RARE : x < 0.959 ? SkinWraps.Rarity.MYTHICAL : x < 0.991 ? SkinWraps.Rarity.LEGENDARY
				: x < 0.9974 ? SkinWraps.Rarity.ANCIENT : null;
		if (t == null) return new Tile(ItemStack.EMPTY, GOLD, "★ Rare Special Item ★", null);
		for (int k = t.ordinal(); k >= 0; k--) {
			var list = c.guns().get(SkinWraps.Rarity.values()[k]);
			if (list == null || list.isEmpty()) continue;
			Cases.Entry e = list.get(rng.nextInt(list.size()));
			ItemStack s = Cases.stack(e, 0);
			return new Tile(s, 0xFF000000 | SkinWraps.Rarity.values()[k].color, s.getHoverName().getString(), e);
		}
		return contents.isEmpty() ? new Tile(ItemStack.EMPTY, GOLD, "", null) : contents.get(rng.nextInt(contents.size()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, 0xE0101418);
		var font = Minecraft.getInstance().font;
		g.text(font, c.name(), width / 2 - font.width(c.name()) / 2, 12, 0xFFFFFFFF, true);
		if (reel == null) contents(g, font);
		else reel(g, font);
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	/** The contents grid, best first, then the rare special item line. */
	private void contents(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font) {
		int cols = Math.max(1, Math.min(6, (width - 40) / (TILE + 8))), x0 = width / 2 - cols * (TILE + 8) / 2, y = 32;
		for (int i = 0; i < contents.size(); i++) {
			int x = x0 + (i % cols) * (TILE + 8), ty = y + (i / cols) * (TILE / 2 + 14);
			tile(g, font, contents.get(i), x, ty, TILE, TILE / 2 + 8);
		}
		int rows = (contents.size() + cols - 1) / cols;
		if (!c.special().isEmpty()) {
			String s = "★ Rare Special Item: " + (c.special().get(0).gloves() ? "gloves" : "knives") + " ★";
			g.text(font, s, width / 2 - font.width(s) / 2, y + rows * (TILE / 2 + 14) + 6, GOLD, true);
		}
	}

	private void tile(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, Tile t, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, 0xFF22272E);
		g.fill(x, y + h - 3, x + w, y + h, t.color());
		// CS2's picture of the gun in its skin (4:3), the item's own icon until it's loaded
		var pic = t.entry() == null ? null : CaseImages.get(t.entry());
		if (pic != null) {
			int pw = w - 4, ph = Math.min(h - 16, pw * 3 / 4), px = x + (w - pw) / 2;
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, px, y + 2, 0, 0, pw, ph, pw, ph);
		} else if (!t.stack().isEmpty()) g.item(t.stack(), x + w / 2 - 8, y + 4);
		String n = font.plainSubstrByWidth(t.name(), w - 4);
		g.text(font, n, x + w / 2 - font.width(n) / 2, y + h - 13, 0xFFE0E0E0, false);
	}

	/** The reel, eased to a stop on the result; ticks as each tile passes the line. */
	private void reel(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font) {
		double t = Math.min(1, (System.nanoTime() - spinStart) / 1e9 / SPIN_SECONDS);
		double ease = 1 - Math.pow(1 - t, 4);
		int step = TILE + 6, cy = height / 2 - TILE / 2;
		double target = landing * step + step / 2.0 - 10; // stop a little off centre, like CS2
		double off = ease * target;
		int tick = (int) (off / step);
		if (tick != lastTick && t < 1) {
			lastTick = tick;
			var p = Minecraft.getInstance().player;
			if (p != null) p.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.3f, 1.8f);
		}
		for (int i = 0; i < reel.size(); i++) {
			int x = (int) (width / 2 + i * step - off);
			if (x + TILE < 0 || x > width) continue;
			tile(g, font, reel.get(i), x, cy, TILE, TILE);
		}
		g.fill(width / 2 - 1, cy - 6, width / 2 + 1, cy + TILE + 6, 0xFFFFD040);
		if (t >= 1) {
			String s = "You got: " + result;
			g.text(font, s, width / 2 - font.width(s) / 2, cy + TILE + 16, resultColor, true);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
