import com.paddisplay.app.desktop.DesktopState;
import com.paddisplay.app.desktop.WindowPlacement;
import com.paddisplay.app.desktop.EscapeKeyPolicy;
import com.paddisplay.app.system.CoordinateSpaceProbe;
import java.util.Random;

/** Runtime boundaries that can be verified without a ROM or physical display. */
public final class DesktopSmoke {
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        require(EscapeKeyPolicy.INSTANCE.matches(139, 0), "F9 unavailable");
        require(EscapeKeyPolicy.INSTANCE.matches(139, 8), "Fn-F9 meta flag rejected");
        require(!EscapeKeyPolicy.INSTANCE.matches(57, 0), "left Alt captured");
        require(!EscapeKeyPolicy.INSTANCE.matches(139, 2), "Alt-F9 captured");
        require(!EscapeKeyPolicy.INSTANCE.matches(139, 0x1000), "Ctrl-F9 captured");
        String[] placements = {"left", "right", "center", "fill", "up", "down", "moveLeft", "moveRight", "larger", "smaller"};
        Random random = new Random(19);
        for (int i = 0; i < 500; i++) {
            int w = 320 + random.nextInt(5000), h = 240 + random.nextInt(3000);
            int bar = random.nextInt(h * 2);
            int usable = h - Math.min(bar, h - 120);
            for (String placement : placements) {
                int[] bounds = WindowPlacement.INSTANCE.bounds(w, h, bar, placement,
                    new int[] {-random.nextInt(w), -random.nextInt(h), random.nextInt(w * 2), random.nextInt(h * 2)});
                require(bounds[0] >= 0 && bounds[1] >= 0 && bounds[2] <= w && bounds[3] <= usable, "off-screen window");
                require(bounds[2] - bounds[0] >= 160 && bounds[3] - bounds[1] >= 120, "invalid window size");
            }
        }
        boolean failed = false;
        try { WindowPlacement.INSTANCE.bounds(100, 100, 0, "left", null); } catch (IllegalArgumentException expected) { failed = true; }
        require(failed, "unsupported viewport accepted");
        CoordinateSpaceProbe probe = new CoordinateSpaceProbe(command -> "");
        String dump = "mDisplayId=50\n cur=800x600\nmDisplayId=5\n cur=3840x2160\nmDisplayId=0\n cur=2520x1680\n";
        require(probe.parseInjectionSpace(dump, 5).getWidth() == 3840, "display 5 matched display 50");
        require(probe.parseInjectionSpace(dump, 50).getWidth() == 800, "display 50 wrong");
        require(probe.parseInjectionSpace(dump, 9) == null, "missing display returned coordinates");
        String json = "{\"ok\":true,\"tasks\":[{\"id\":17,\"package\":\"com.demo\",\"mode\":5,\"left\":0,\"top\":0,\"right\":960,\"bottom\":800}]}";
        require(DesktopState.INSTANCE.parseTasks(json).get(0).getId() == 17, "task identity lost");
        failed = false;
        try { DesktopState.INSTANCE.parseTasks("{\"ok\":false,\"error\":\"denied\"}"); } catch (IllegalStateException expected) { failed = true; }
        require(failed, "task read failure became an empty success");
        System.out.println("PASS: F9/Fn policy and Alt pass-through, 5000 placements, viewport guards, task protocol");
    }
}
