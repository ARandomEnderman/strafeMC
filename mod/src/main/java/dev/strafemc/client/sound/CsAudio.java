package dev.strafemc.client.sound;

import com.mojang.blaze3d.audio.SoundBuffer;
import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AssetCache;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import net.minecraft.resources.Identifier;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decoded CS2 audio for Minecraft's sound engine. Sounds are addressed as {@code strafemc_cs:<vsnd path without .vsnd>};
 * the buffer library mixin asks us for them instead of looking in resource packs. Files come from the local VRF cache.
 */
public final class CsAudio {
	public static final String NAMESPACE = "strafemc_cs";
	private static final Map<String, CompletableFuture<SoundBuffer>> BUFFERS = new ConcurrentHashMap<>();
	private static volatile boolean ready;

	private CsAudio() {}

	public static boolean ready() {
		return ready;
	}

	/** Extract all player sounds (1.5 s, once) and load the sound event definitions. Runs in the background. */
	public static void prepareAsync() {
		Thread t = new Thread(() -> {
			try {
				Path marker = StrafeConfig.cacheDir().resolve("sounds/player/.extracted");
				if (!Files.exists(marker)) {
					AssetCache.extractFolder("sounds/player/");
					Files.createDirectories(marker.getParent());
					Files.writeString(marker, "ok");
				}
				CsSoundEvents.load();
				ready = true;
				try { // bullet impacts: surfaces + decal groups (same background step)
					dev.strafemc.data.Surfaces.load();
					dev.strafemc.client.impact.DecalLibrary.load();
				} catch (Exception e) {
					StrafeMc.LOG.error("[strafemc] CS2 surfaces/decals unavailable", e);
				}
			} catch (Exception e) {
				StrafeMc.LOG.error("[strafemc] CS2 sounds unavailable", e);
			}
		}, "strafemc-sounds");
		t.setDaemon(true);
		t.start();
	}

	public static Identifier id(String vsndPath) {
		String p = vsndPath.endsWith(".vsnd") ? vsndPath.substring(0, vsndPath.length() - 5) : vsndPath;
		return Identifier.fromNamespaceAndPath(NAMESPACE, p.toLowerCase());
	}

	/** Called by the buffer-library mixin with MC's resolved path (sounds/<path>.ogg). */
	public static CompletableFuture<SoundBuffer> buffer(Identifier mcPath) {
		String p = mcPath.getPath();
		if (p.startsWith("sounds/")) p = p.substring(7);
		if (p.endsWith(".ogg")) p = p.substring(0, p.length() - 4);
		String key = p;
		return BUFFERS.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> decode(k)));
	}

	private static SoundBuffer decode(String path) {
		Path base = StrafeConfig.cacheDir().resolve(path);
		try {
			Path wav = Path.of(base + ".wav"), mp3 = Path.of(base + ".mp3");
			if (!Files.exists(wav) && !Files.exists(mp3)) AssetCache.ensureDecompiled(path + ".vsnd_c", wav); // weapons etc.: extracted on first use
			if (Files.exists(wav)) return wav(Files.readAllBytes(wav));
			if (Files.exists(mp3)) return mp3(mp3);
			throw new IOException("no extracted audio for " + path);
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] could not decode CS2 sound {}: {}", path, e.toString());
			return new SoundBuffer(ByteBuffer.allocateDirect(2), new AudioFormat(44100, 16, 1, true, false));
		}
	}

	/** RIFF/WAVE PCM16 -> mono buffer (positional audio needs mono). */
	private static SoundBuffer wav(byte[] b) throws IOException {
		ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
		int channels = 1, rate = 44100, bits = 16, pos = 12;
		while (pos + 8 <= b.length) {
			int id = bb.getInt(pos), size = bb.getInt(pos + 4);
			if (id == 0x20746d66) { // "fmt "
				channels = bb.getShort(pos + 10);
				rate = bb.getInt(pos + 12);
				bits = bb.getShort(pos + 22);
			} else if (id == 0x61746164) { // "data"
				if (bits != 16) throw new IOException("unsupported WAV bit depth " + bits);
				int frames = Math.min(size, b.length - pos - 8) / (2 * channels);
				short[] mono = new short[frames];
				for (int f = 0; f < frames; f++) {
					int sum = 0;
					for (int c = 0; c < channels; c++) sum += bb.getShort(pos + 8 + (f * channels + c) * 2);
					mono[f] = (short) (sum / channels);
				}
				return pcm(mono, rate);
			}
			pos += 8 + size + (size & 1);
		}
		throw new IOException("WAV without data chunk");
	}

	private static SoundBuffer mp3(Path file) throws Exception {
		try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
			Bitstream bs = new Bitstream(in);
			Decoder dec = new Decoder();
			ByteArrayOutputStream mono = new ByteArrayOutputStream();
			int rate = 44100;
			Header h;
			while ((h = bs.readFrame()) != null) {
				SampleBuffer sb = (SampleBuffer) dec.decodeFrame(h, bs);
				int ch = sb.getChannelCount();
				rate = sb.getSampleFrequency();
				short[] s = sb.getBuffer();
				for (int i = 0; i + ch - 1 < sb.getBufferLength(); i += ch) {
					int sum = 0;
					for (int c = 0; c < ch; c++) sum += s[i + c];
					short v = (short) (sum / ch);
					mono.write(v & 0xff);
					mono.write((v >> 8) & 0xff);
				}
				bs.closeFrame();
			}
			byte[] d = mono.toByteArray();
			ByteBuffer out = ByteBuffer.allocateDirect(d.length).order(ByteOrder.LITTLE_ENDIAN);
			out.put(d).flip();
			return new SoundBuffer(out, new AudioFormat(rate, 16, 1, true, false));
		}
	}

	private static SoundBuffer pcm(short[] mono, int rate) {
		ByteBuffer out = ByteBuffer.allocateDirect(mono.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (short v : mono) out.putShort(v);
		out.flip();
		return new SoundBuffer(out, new AudioFormat(rate, 16, 1, true, false));
	}
}
