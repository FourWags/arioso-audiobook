package app.arioso.audiobook;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

/** Stable launcher aliases. PackageManager is the persistent source of truth, including after updates. */
final class LauncherIcons {
    static final class Icon {
        final String alias, name;
        final int drawable;
        final boolean enabledByDefault;
        Icon(String alias, String name, int drawable, boolean enabledByDefault) {
            this.alias = alias; this.name = name; this.drawable = drawable;
            this.enabledByDefault = enabledByDefault;
        }
    }
    static final Icon[] ALL = {
        new Icon("LauncherDefault", "Original Arioso", R.drawable.ic_arioso, true),
        new Icon("LauncherPage", "Page & sound", R.mipmap.ic_page, false),
        new Icon("LauncherArcs", "Sound arcs", R.mipmap.ic_arcs, false),
        new Icon("LauncherShiba", "Shiba headphones", R.mipmap.ic_shiba, false)
    };
    static ComponentName component(Context context, Icon icon) {
        return new ComponentName(context.getPackageName(), "app.arioso.audiobook." + icon.alias);
    }
    static boolean enabled(Context context, Icon icon) {
        int state = context.getPackageManager().getComponentEnabledSetting(component(context, icon));
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon.enabledByDefault);
    }
    static Icon current(Context context) {
        for (Icon icon : ALL) if (enabled(context, icon)) return icon;
        return ALL[0];
    }
    static void select(Context context, Icon selected) {
        boolean known = false;
        for (Icon icon : ALL) if (icon == selected) known = true;
        if (!known) throw new IllegalArgumentException("Unknown launcher icon");
        PackageManager pm = context.getPackageManager();
        if (Build.VERSION.SDK_INT >= 33) {
            Api33.select(context, selected);
        } else {
            // Enable the replacement first: a failed disable can leave a duplicate, never no launcher.
            // Roll back to exactly one previous icon if the legacy multi-call operation fails.
            Icon previous = current(context);
            try {
                set(pm, component(context, selected), true);
                for (Icon icon : ALL) if (icon != selected) set(pm, component(context, icon), false);
            } catch (RuntimeException failure) {
                try {
                    set(pm, component(context, previous), true);
                    for (Icon icon : ALL) if (icon != previous) set(pm, component(context, icon), false);
                } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
    private static void set(PackageManager pm, ComponentName name, boolean enabled) {
        pm.setComponentEnabledSetting(name, enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }
    // Isolate newer platform types without adding an AndroidX runtime dependency.
    @android.annotation.SuppressLint("UseRequiresApi")
    @TargetApi(33) private static final class Api33 {
        static void select(Context context, Icon selected) {
            List<PackageManager.ComponentEnabledSetting> changes = new ArrayList<>();
            for (Icon icon : ALL) changes.add(new PackageManager.ComponentEnabledSetting(
                    component(context, icon), icon == selected ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP));
            context.getPackageManager().setComponentEnabledSettings(changes);
        }
    }
    private LauncherIcons() { }
}
