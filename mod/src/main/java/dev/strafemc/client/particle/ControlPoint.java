package dev.strafemc.client.particle;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A particle control point: position plus orientation (Source axes: +X forward, +Z up). */
public final class ControlPoint {
	public final Vector3f position = new Vector3f(), positionPrevious = new Vector3f(), orientation = new Vector3f();
	public Quaternionf rotation; // full rotation when known (attachments); else derived from orientation
	float changeTime = -1, previousStepTime;

	public void set(Vector3f pos, Quaternionf rot, PState clock) {
		if (!pos.equals(position) && clock != null) changeTime = clock.age;
		position.set(pos);
		rotation = rot == null ? null : new Quaternionf(rot);
		orientation.set(rot == null ? new Vector3f() : rot.transform(new Vector3f(1, 0, 0)));
	}

	float changeAge(PState s) {
		return s.age - changeTime;
	}

	Vector3f velocity() {
		return previousStepTime > 0 ? new Vector3f(position).sub(positionPrevious).div(previousStepTime) : new Vector3f();
	}

	boolean hasOrientation() {
		return rotation != null || orientation.lengthSquared() > 0;
	}

	Quaternionf rotationOrIdentity() {
		if (rotation != null) return new Quaternionf(rotation);
		return orientation.lengthSquared() > 0 ? PMath.forwardToRotation(orientation) : new Quaternionf();
	}

	/** World transform (rotation, then translation). */
	Matrix4f transform(boolean useOrientation) {
		Matrix4f m = new Matrix4f().translation(position);
		if (useOrientation && hasOrientation()) m.rotate(rotationOrIdentity());
		return m;
	}
}
