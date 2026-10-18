package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.SprayItem;
import dev.strafemc.weapon.Sprays;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** The sealed graffiti in your hand: its picture and name, and Unseal (then it can be sprayed, and has no worth any more). */
public final class UnsealScreen extends Screen {
	public UnsealScreen() {
		super(Component.literal("Unseal Graffiti"));
	}

	private static ItemStack held() {
		var p = Minecraft.getInstance().player;
		if (p == null) return ItemStack.EMPTY;
		for (var hand : net.minecraft.world.InteractionHand.values()) {
			ItemStack s = p.getItemInHand(hand);
			if (s.getItem() instanceof SprayItem && SprayItem.sealed(s)) return s;
		}
		return ItemStack.EMPTY;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Unseal"), b -> {
			if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("spray_unseal", ""));
			dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/claim_gift_01", 0.3f);
			onClose();
		}).bounds(width / 2 - 75, height - 52, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, StrafeSettingsScreen.BG);
		var font = minecraft.font;
		ItemStack s = held();
		if (s.isEmpty()) {
			onClose();
			return;
		}
		Sprays.Spray sp = Sprays.get(SprayItem.spray(s));
		int size = Math.min(160, height - 120), cx = width / 2, top = Math.max(20, height / 2 - size / 2 - 30);
		var tex = sp == null ? null : dev.strafemc.client.impact.SprayDecals.texture(sp.material());
		Sprays.Tint t = sp != null && sp.tintable() ? Sprays.tint(SprayItem.tint(s)) : null;
		if (tex != null)
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, cx - size / 2, top, 0, 0, size, size, size, size, size, size,
					t != null ? 0xFF000000 | t.color() : -1);
		else g.item(s, cx - 8, top + size / 2 - 8);
		Component name = s.getHoverName();
		g.text(font, name, cx - font.width(name) / 2, top + size + 8, -1, true);
		String note = "Once unsealed it can be sprayed, but not sold, upgraded or traded.";
		g.text(font, note, cx - font.width(note) / 2, top + size + 22, 0xFF909090, false);
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
