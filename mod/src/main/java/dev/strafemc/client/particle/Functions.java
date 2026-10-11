package dev.strafemc.client.particle;

/** Factory: .vpcf function class name to its port (null = unsupported, logged once by PSystem). */
final class Functions {
	private Functions() {}

	static PFunction create(String kind, String cls, Pd p) {
		PFunction f = kind.equals("initializer") ? Initializers.create(cls, p) : Operators.create(kind, cls, p);
		return f != null ? f : MoreFunctions.create(kind, cls, p);
	}
}
