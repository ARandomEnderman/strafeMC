package dev.strafemc.weapon;

/** Prints the AK's CS2 spray: table entries and the punch (bullet offset) at each of 30 full-auto shots. gradlew vmCheck -PvmMain=dev.strafemc.weapon.SprayCheck */
public final class SprayCheck {
	public static void main(String[] a) {
		dev.strafemc.data.WeaponData.loadAsync();
		for (int i = 0; i < 600 && !dev.strafemc.data.WeaponData.loaded(); i++) try { Thread.sleep(100); } catch (InterruptedException e) { return; }
		Recoil r = Recoil.of("weapon_ak47");
		WeaponStats w = WeaponStats.of("weapon_ak47");
		System.out.printf("cycle %.4f s, fullAuto %s%n", w.cycleTime(), w.fullAuto());
		for (int i = 0; i < 12; i++) System.out.printf("table[%d] angle %.3f mag %.3f%n", i, r.angle[0][i], r.magnitude[0][i]);
		AimPunch p = new AimPunch();
		p.reset(0);
		StringBuilder csv = new StringBuilder();
		for (int shot = 0; shot < 30; shot++) {
			double t = shot * w.cycleTime();
			p.advance(t);
			// the bullet of this shot uses the punch before its own kick (eye angles + punch)
			System.out.printf("shot %2d: pitch %+7.3f yaw %+7.3f%n", shot + 1, p.angle[0], p.angle[1]);
			csv.append(p.angle[0]).append(',').append(p.angle[1]).append('\n');
			p.kick(t, r.angle[0][shot & 63], r.magnitude[0][shot & 63]);
		}
		try {
			java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "ak_spray.csv"), csv);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
