package dev.strafemc.client.particle;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Map;

/** Source 2 transform input (a control point, with or without its orientation). Port of VRF ITransformProvider (MIT). */
final class Xform {
	final int cp;
	final boolean useOrientation, identity;

	Xform(int cp, boolean useOrientation, boolean identity) {
		this.cp = cp;
		this.useOrientation = useOrientation;
		this.identity = identity;
	}

	static final Xform CP0 = new Xform(0, true, false);

	static Xform parse(Pd d, String key) {
		Object o = d.raw(key);
		if (o instanceof Map<?, ?>) {
			Pd p = d.nested(o);
			String type = p.str("m_nType", "PT_TYPE_CONTROL_POINT");
			if (type.equals("PT_TYPE_CONTROL_POINT") || type.equals("PT_TYPE_CONTROL_POINT_RANGE"))
				return new Xform(p.i("m_nControlPoint", 0), p.b("m_bUseOrientation", true), false);
			return new Xform(0, false, true);
		}
		if (o instanceof Number n) return new Xform(n.intValue(), true, false);
		return new Xform(0, false, true);
	}

	Matrix4f matrix(PState s) {
		return identity ? new Matrix4f() : s.cp(cp).transform(useOrientation);
	}

	/** Forward (+X) of the control point, or null without orientation. */
	Vector3f orientation(PState s) {
		if (identity) return null;
		ControlPoint c = s.cp(cp);
		if (!useOrientation || !c.hasOrientation()) return null;
		return c.rotationOrIdentity().transform(new Vector3f(1, 0, 0));
	}
}
