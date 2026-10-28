package io.github.liuran001.mmliquidglass;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The few values the bar exposes for tuning, read from the host app's own
 * SharedPreferences.
 *
 * <p>Nothing in the module writes this file — there is no settings UI — but it
 * lets the layout be changed without a rebuild, which is the part that is
 * really a matter of taste. Each host keeps its own copy, since the file is
 * read through that app's context and lands in that app's data dir.
 *
 * <p>How to change a value, e.g. switching the bar to the drawer style for
 * WeChat from a root shell:
 *
 * <pre>
 * su -c 'echo "&lt;map&gt;&lt;string name=\"barStyle\"&gt;drawer&lt;/string&gt;&lt;/map&gt;" \
 *          > /data/data/com.tencent.mm/shared_prefs/wx_liquid_glass_cfg.xml'
 * </pre>
 *
 * <p>or through {@link LiquidGlassModule}'s remote preferences when the module
 * is enabled in a framework that provides them. Inside the host process the
 * remote store is read-only, so it is only ever a fallback for values the local
 * file does not carry.
 */
final class GlassConfig {

    /** Named before QQ was a target; kept so existing WeChat setups still read. */
    private static final String PREFS = "wx_liquid_glass_cfg";

    /** Group name of the remote preferences, as shown by LSPosed. */
    private static final String REMOTE_GROUP = "config";

    /** Style value: every tab its own column, equal width (the original look). */
    static final String STYLE_DEFAULT = "default";

    /**
     * Style value: every tab an icon-sized column and the selected one an
     * expanding pill that also shows its label — FolkPatch's drawer bar.
     */
    static final String STYLE_DRAWER = "drawer";

    /** Distance between the bottom of the glass pill and the screen edge, dp. */
    static volatile int barOffsetDp = 12;

    /**
     * {@link #STYLE_DEFAULT} or {@link #STYLE_DRAWER}.
     *
     * <p>Read at install and at the start of every resume, so a restart of the
     * host app is all it takes for a change to land; the geometry itself is not
     * rebuilt in place because switching the row between the two shapes means
     * re-measuring the host and the native bar underneath it.
     */
    static volatile String barStyle = STYLE_DEFAULT;

    private GlassConfig() {
    }

    static boolean isDrawer() {
        return STYLE_DRAWER.equals(barStyle);
    }

    static void load(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            barOffsetDp = p.getInt("barOffsetDp", barOffsetDp);
            String style = p.getString("barStyle", barStyle);
            if (style != null) {
                barStyle = style;
            }
        } catch (Throwable t) {
            LiquidGlassModule.logErr("config load failed", t);
        }
        loadRemote();
    }

    /**
     * Fills in anything the host's own SharedPreferences did not carry from the
     * module's remote store.
     *
     * <p>Only values that are still at their defaults are taken, so the local
     * file — which reaches both hosts — always wins where it says something.
     * The store is absent on frameworks without remote preferences, which is
     * the normal case and not worth a log line.
     */
    private static void loadRemote() {
        try {
            SharedPreferences remote = LiquidGlassModule.remotePreferences(REMOTE_GROUP);
            if (remote == null) {
                return;
            }
            String style = remote.getString("barStyle", barStyle);
            if (style != null && !STYLE_DEFAULT.equals(barStyle)) {
                return; // the local file already decided
            }
            if (style != null) {
                barStyle = style;
            }
            if (barOffsetDp == 12) {
                barOffsetDp = remote.getInt("barOffsetDp", barOffsetDp);
            }
        } catch (Throwable ignored) {
            // No remote store: nothing to merge.
        }
    }
}
