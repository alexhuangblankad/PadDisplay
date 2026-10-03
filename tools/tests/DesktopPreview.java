package com.paddisplay.qa;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;

/** Visual fixture only: no physical display, task control or mouse binding is tested here. */
public class DesktopPreview extends Instrumentation {
    private boolean escape;
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); escape = args != null && "true".equals(args.getString("escape")); start(); }
    @Override public void onStart() {
        try {
            runOnMainSync(() -> {
                try {
                    ClassLoader loader = getTargetContext().getClassLoader();
                    Object snapshot = loader.loadClass("com.paddisplay.app.desktop.DesktopSnapshot").getConstructor().newInstance();
                    set(snapshot, "running", true); set(snapshot, "displayId", 0);
                    Class<?> appClass = loader.loadClass("com.paddisplay.app.system.SystemDisplayService$LaunchableApp");
                    ArrayList<Object> apps = new ArrayList<>(); HashSet<String> favorites = new HashSet<>();
                    for (ResolveInfo row : getTargetContext().getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                        String pkg = row.activityInfo.packageName;
                        if (pkg.equals("com.paddisplay.app")) continue;
                        apps.add(appClass.getConstructor(String.class,String.class,String.class).newInstance(pkg,row.loadLabel(getTargetContext().getPackageManager()).toString(),pkg+"/"+row.activityInfo.name));
                        if (pkg.equals("com.android.chrome") || pkg.equals("com.google.android.apps.docs") || pkg.equals("com.google.android.apps.photos")) favorites.add(pkg);
                    }
                    set(snapshot, "apps", apps); set(snapshot, "favorites", favorites);
                    Class<?> stateClass = loader.loadClass("com.paddisplay.app.desktop.DesktopState");
                    Object flow = stateClass.getMethod("getState").invoke(stateClass.getField("INSTANCE").get(null));
                    loader.loadClass("kotlinx.coroutines.flow.MutableStateFlow").getMethod("setValue",Object.class).invoke(flow,snapshot);
                    getTargetContext().startActivity(new Intent().setClassName("com.paddisplay.app",escape ? "com.paddisplay.app.desktop.EscapeNavigationActivity" : "com.paddisplay.app.desktop.DesktopActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            Thread.sleep(60000);
            finish(0, new Bundle());
        } catch (Exception error) { Bundle result = new Bundle(); result.putString("error",error.toString()); finish(1,result); }
    }
}
