package fr.palawi.playerdatarescue.core;

public final class ModInfo {
    public static final String MOD_ID = "playerdatarescue";

    private ModInfo() {}

    public static String loadedLine(String loader, String band) {
        if (loader == null || loader.isBlank()) {
            throw new IllegalArgumentException("loader must not be blank");
        }
        if (band == null || band.isBlank()) {
            throw new IllegalArgumentException("band must not be blank");
        }
        return "[" + MOD_ID + "] loaded on " + loader + ", band " + band;
    }
}
