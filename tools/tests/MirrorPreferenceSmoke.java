import com.paddisplay.app.system.MirrorPreference;
public final class MirrorPreferenceSmoke {
  static class Fake {
    String value="1"; boolean deny=false; int writes=0;
    String run(String cmd) {
      if (!cmd.startsWith("/system/bin/settings --user 10 ")) throw new AssertionError(cmd);
      if (cmd.contains(" get secure ")) return value;
      writes++; if (!deny) value=cmd.substring(cmd.length()-1); return "";
    }
    MirrorPreference pref() { return new MirrorPreference(this::run); }
  }
  static void check(boolean b) { if (!b) throw new AssertionError(); }
  static void fails(Runnable r) { try { r.run(); } catch (IllegalArgumentException|IllegalStateException ok) { return; } throw new AssertionError("accepted invalid state"); }
  public static void main(String[] args) {
    Fake f=new Fake(); MirrorPreference p=f.pref();
    p.disable(10,"1"); check(f.value.equals("0")); p.restore(10,"1"); check(f.value.equals("1"));
    int n=f.writes; p.restore(10,"1"); check(n==f.writes);
    f.deny=true; fails(()->p.disable(10,"1")); check(f.value.equals("1"));
    f.value="0"; fails(()->p.restore(10,"1")); check(f.value.equals("0"));
    f.deny=false; f.value="null"; n=f.writes; fails(()->p.restore(10,"1")); check(n==f.writes);
    f.value="Permission denial"; fails(()->p.read(10));
    f.value="0"; fails(()->p.disable(10,"1")); fails(()->p.disable(10,"0"));
    fails(()->p.read(-1));
    System.out.println("MirrorPreferenceSmoke PASS: transition, denied write, read validation, restore, concurrent change, user scope");
  }
}
