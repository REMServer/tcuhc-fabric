package me.fallenbreath.tcuhc.util;

/** Geometry uses Minecraft yaw: zero faces south, positive yaw turns west. */
public final class CompassHint {
	private static final String[] DIRECTIONS = {
		"↑ 前方", "↗ 右前方", "→ 右侧", "↘ 右后方",
		"↓ 后方", "↙ 左后方", "← 左侧", "↖ 左前方"
	};

	private CompassHint() {}

	public static String format(double dx, double dz, float yaw, long ageSeconds) {
		double distance = Math.hypot(dx, dz);
		double relative = Math.toDegrees(Math.atan2(-dx, dz)) - yaw;
		int sector = Math.floorMod((int) Math.floor((relative + 22.5) / 45.0), 8);
		String direction = distance < 1 ? "就在附近" : DIRECTIONS[sector];
		String range = distance < 5 ? "不足 5 格" : "约 " + Math.max(10, Math.round(distance / 10) * 10) + " 格";
		return "敌人 " + direction + " · " + range + " · " + Math.max(0, ageSeconds) + " 秒前定位";
	}
}
