package space.glidetv.launcher;

public final class ReadabilityTest {
    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        float night = Readability.sceneLuminance(0xff080e27, 0xff24334d);
        float day = Readability.sceneLuminance(0xff64b2e9, 0xffd5ebf5);
        expect(day > night, "day scene should be brighter than night");
        expect(Readability.scrimAlpha(day) > Readability.scrimAlpha(night), "bright scenes need a stronger scrim");
        expect(Readability.videoLuminance(true, 0) > Readability.videoLuminance(true, 61), "rainy video should be darker than clear video");
        expect(Readability.videoLuminance(true, 0) > Readability.videoLuminance(false, 0), "day video should be brighter than night video");
        expect(Readability.scrimAlpha(-1) == 30, "scrim lower bound");
        expect(Readability.scrimAlpha(2) == 148, "scrim upper bound");
        System.out.println("Readability checks passed");
    }
}
