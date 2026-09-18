package me.fallenbreath.tcuhc.util;

import java.util.List;

/** Template-local floor positions; players stand one block above them. */
public enum LobbyDefinition {
	THE_VALLEY("the_valley", "The Valley", new Floor(30, 13, 37), new Floor(32, 13, 37), new Floor(33, 13, 37)),
	SAK_AUTH("sak_auth", "SakAuthLobby", new Floor(39, 9, 58), new Floor(41, 9, 58), new Floor(43, 9, 58)),
	IC_WAITING("ic_waiting", "IC WaitingLobby", new Floor(10, 8, 21), new Floor(11, 8, 21), new Floor(12, 8, 21)),
	ICELAND("iceland", "Iceland", new Floor(24, 29, 23), new Floor(26, 29, 23), new Floor(28, 29, 23));

	public record Floor(int x, int y, int z) {}
	public final String id;
	public final String displayName;
	public final List<Floor> floors;

	LobbyDefinition(String id, String displayName, Floor... floors) {
		this.id = id;
		this.displayName = displayName;
		this.floors = List.of(floors);
	}

	public static LobbyDefinition byId(String id) {
		for (LobbyDefinition lobby : values()) if (lobby.id.equals(id)) return lobby;
		throw new IllegalArgumentException("Unknown lobby template: " + id);
	}
}
