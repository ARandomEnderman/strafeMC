package dev.strafemc.client.particle;

import org.joml.Vector3f;

/**
 * The world as particle functions see it (traces against level geometry, the local player's eyes), in WORLD simulation
 * space (Source units, z up). Effects installs the Minecraft implementation; without one, traces miss.
 */
public interface PWorld {
	/** A trace hit: where, its surface normal, the fraction of the way it got, and whether it started inside something. */
	record Hit(Vector3f position, Vector3f normal, float fraction, boolean inside) {}

	/** Traces from -> to against solid blocks (and water when asked); null when nothing is in the way. */
	Hit trace(Vector3f from, Vector3f to, boolean water);

	/** The local player's eye position and view direction (forward, unit). */
	Vector3f eyePosition();

	Vector3f eyeForward();

	PWorld NONE = new PWorld() {
		@Override
		public Hit trace(Vector3f from, Vector3f to, boolean water) {
			return null;
		}

		@Override
		public Vector3f eyePosition() {
			return new Vector3f();
		}

		@Override
		public Vector3f eyeForward() {
			return new Vector3f(1, 0, 0);
		}
	};
}
