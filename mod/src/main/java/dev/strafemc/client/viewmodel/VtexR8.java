package dev.strafemc.client.viewmodel;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads a compiled CS2 texture holding one byte per pixel (the ID maps of CS2's newer glove finishes, which the
 * bundled converter can't decode yet): the pixels follow the resource's last block, LZ4-compressed or raw. Square
 * textures only; the byte goes to all three colour channels.
 */
public final class VtexR8 {
	private VtexR8() {}

	public static BufferedImage read(byte[] file) {
		ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
		int blockOffsetField = 8, blockOffset = b.getInt(blockOffsetField), blockCount = b.getInt(12);
		int end = 0;
		for (int i = 0; i < blockCount; i++) {
			int at = blockOffsetField + blockOffset + i * 12;
			int off = at + 4 + b.getInt(at + 4), size = b.getInt(at + 8);
			end = Math.max(end, off + size);
		}
		byte[] in = java.util.Arrays.copyOfRange(file, end, file.length);
		byte[] px = isSquare(in.length) ? in : lz4(in);
		int side = (int) Math.round(Math.sqrt(px.length));
		if (side * side != px.length || side == 0) throw new IllegalArgumentException("not a square 8-bit texture (" + px.length + " bytes)");
		BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < side; y++)
			for (int x = 0; x < side; x++) {
				int v = px[y * side + x] & 255;
				img.setRGB(x, y, 0xFF000000 | v << 16 | v << 8 | v);
			}
		return img;
	}

	private static boolean isSquare(int n) {
		int s = (int) Math.round(Math.sqrt(n));
		return n >= 64 * 64 && s * s == n && Integer.bitCount(s) == 1;
	}

	/** LZ4 block format. */
	static byte[] lz4(byte[] src) {
		byte[] out = new byte[Math.max(1024, src.length * 8)];
		int i = 0, o = 0;
		while (i < src.length) {
			int token = src[i++] & 255, lit = token >>> 4;
			if (lit == 15) {
				int x;
				do lit += x = src[i++] & 255;
				while (x == 255);
			}
			out = room(out, o + lit);
			System.arraycopy(src, i, out, o, lit);
			i += lit;
			o += lit;
			if (i >= src.length) break;
			int offset = (src[i] & 255) | (src[i + 1] & 255) << 8;
			i += 2;
			int len = token & 15;
			if (len == 15) {
				int x;
				do len += x = src[i++] & 255;
				while (x == 255);
			}
			len += 4;
			out = room(out, o + len);
			for (int k = 0; k < len; k++, o++) out[o] = out[o - offset]; // may overlap: byte by byte
		}
		return java.util.Arrays.copyOf(out, o);
	}

	private static byte[] room(byte[] a, int need) {
		return need <= a.length ? a : java.util.Arrays.copyOf(a, Math.max(need, a.length * 2));
	}
}
