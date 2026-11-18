package space.glidetv.launcher;

import java.util.Arrays;

public final class IconPaletteTest {
    public static void main(String[] args) {
        expect(IconPalette.NEUTRAL, IconPalette.fromPixels(null), "missing icon");
        expect(IconPalette.NEUTRAL, IconPalette.fromPixels(new int[0]), "empty icon");
        expect(IconPalette.NEUTRAL, IconPalette.fromPixels(new int[]{0xff000000,0xffffffff,0xffaaaaaa,0x00ff0000,0x70ff0000}), "monochrome and transparent icon");
        int[] netflix = new int[1024]; Arrays.fill(netflix, 0xff080808);
        Arrays.fill(netflix, 400, 500, 0xffe50914);
        expect(0xffe50914, IconPalette.fromPixels(netflix), "red logo on black");
        int[] prime = new int[1024]; Arrays.fill(prime, 0xffffffff);
        Arrays.fill(prime, 200, 300, 0xff00a8e1);
        expect(0xff00a8e1, IconPalette.fromPixels(prime), "blue logo on white");
        int[] spotify = new int[1024]; Arrays.fill(spotify, 0xff1ed760);
        Arrays.fill(spotify, 400, 700, 0xff000000); spotify[0] = 0xffff0000;
        expect(0xff1ed760, IconPalette.fromPixels(spotify), "dominant green beats isolated red");
        expect(0xff00be00, IconPalette.fromPixels(new int[]{0xff006000}), "dark colors remain visible");
        System.out.println("Icon palette checks passed");
    }

    private static void expect(int expected, int actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": expected " + Integer.toHexString(expected) + ", got " + Integer.toHexString(actual));
    }
}
