package io.github.liuran001.mmliquidglass;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;

/**
 * The drawer style: every tab an icon-sized slot, and the selected one an
 * expanding pill that shows its label — FolkPatch's drawer bottom bar.
 *
 * <pre>
 *  ┌──────────────────────────────────────────┐
 *  │ ◉   ☰   ⊕   ◈                            │   row: n icon slots
 *  └──────────────────────────────────────────┘
 *  ┌────────────────────────┐
 *  │ ◉  聊天                │                │   open pill over the selected slot
 *  └────────────────────────┘
 * </pre>
 *
 * <p>The bar does not widen when the selection moves: the capsule is sized for
 * exactly n slots plus one expansion, and the expansion moves between slots.
 * That is what makes the motion read as a drawer opening where the finger is,
 * rather than the whole bar growing and shrinking.
 *
 * <p>The open pill is drawn as an overlay rather than as layout, for two
 * reasons. Reflowing the row would mean re-measuring the host and the app's
 * own bar under it every time a tab is chosen, which both apps fight back on
 * (see the refresh path in {@link LiquidGlassInstaller}); and the app's real
 * tabs must never move, because taps, long-press menus and badges all belong
 * to them. So the columns stay where the app put them and everything widening
 * happens on top — the pill surface, the icon standing in for the selected
 * tab, and, above this again, the droplet panel.
 *
 * <p>Everything here runs on the UI thread; the state is process-wide because
 * a host has exactly one home bar.
 */
final class DrawerBar {

    /** FolkPatch: the open pill is a Material surface, one step off the bar. */
    private static final int PILL_LIGHT = 0xFFF7F7FA;
    private static final int PILL_DARK = 0xFF323236;
    /** Fraction of one column the pill travels per frame at most. */
    private static final float MAX_STEP = 0.55f;

    private static int sColumnWidth;
    private static int sExpandedWidth;
    private static int sExpansionPx;
    private static int sCount = 1;
    private static int sSelected = -1;

    /** Where the pill is, in columns, and where it is heading — same unit. */
    private static float sPosition = -1f;
    private static int sTarget = -1;
    private static boolean sOvershootDone;
    private static float sOvershoot;

    /** Dark pill or light, resolved off the same signal the host's glass uses. */
    private static boolean sNight;

    private static final Paint sPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path sClip = new Path();

    private DrawerBar() {
    }

    /** Drops every reference to a previous Activity's geometry. */
    static void reset() {
        sColumnWidth = 0;
        sExpandedWidth = 0;
        sExpansionPx = 0;
        sCount = 1;
        sSelected = -1;
        sPosition = -1f;
        sTarget = -1;
        sOvershoot = 0f;
        sOvershootDone = true;
    }

    /* ---------------- geometry, published by the installer ---------------- */

    static void setGeometry(int columnWidth, int expandedWidth, int count) {
        setGeometry(columnWidth, expandedWidth, count, sNight);
    }

    static void setGeometry(int columnWidth, int expandedWidth, int count, boolean night) {
        sNight = night;
        if (columnWidth <= 0) {
            return;
        }
        int expansion = Math.max(0, expandedWidth - columnWidth);
        if (columnWidth == sColumnWidth && expansion == sExpansionPx) {
            sCount = Math.max(1, count);
            return;
        }
        boolean firstLayout = sColumnWidth == 0;
        sColumnWidth = columnWidth;
        sExpansionPx = expansion;
        sExpandedWidth = expandedWidth;
        sCount = Math.max(1, count);
        if (sPosition < 0f) {
            sPosition = Math.max(0, sSelected);
        } else if (firstLayout) {
            // Coming from a narrower layout: whatever the old position meant
            // in the old columns, it is not where the new ones put the
            // selection.
            sPosition = Math.max(0, sSelected);
            sTarget = sSelected;
            sOvershootDone = true;
        }
        refresh(null);
    }

    /** Caps an expansion computed against the screen, after the bar is sized. */
    static void setExpansion(int expansionPx) {
        sExpansionPx = Math.max(0, expansionPx);
        sExpandedWidth = sColumnWidth + sExpansionPx;
    }

    static int columnWidth() {
        return sColumnWidth;
    }

    static int expandedWidth() {
        return sExpandedWidth;
    }

    static int expansion() {
        return sExpansionPx;
    }

    /* ---------------- selection ---------------- */

    /**
     * Points the drawer at {@code index}.
     *
     * @param immediate snap instead of sliding, for the first selection of an
     *     install and for a rebound row
     */
    /**
     * Points the drawer at {@code index}.
     *
     * @param immediate re-seat instead of sliding: the first selection of an
     *     install, a rebound row, or a column width that changed under the
     *     pill — sliding from a position measured in the old columns would
     *     land between slots.
     */
    static void setSelected(ViewGroup tabRow, int index, boolean immediate) {
        if (index < 0) {
            return;
        }
        sSelected = index;
        sTarget = index;
        if (immediate || sPosition < 0f) {
            sPosition = index;
            sOvershoot = 0f;
            sOvershootDone = true;
        } else {
            // A small overshoot on arrival is what makes the drawer read as
            // opening rather than as a slider being dragged.
            sOvershoot = index > sPosition ? 0.12f : -0.12f;
            sOvershootDone = false;
        }
        refresh(tabRow);
    }

    /** Advances the slide one frame; called from the bar's per-frame path. */
    static void advance() {
        if (sColumnWidth <= 0 || sSelected < 0) {
            return;
        }
        float target = sTarget + (sOvershootDone ? 0f : sOvershoot);
        float delta = target - sPosition;
        if (delta == 0f) {
            if (!sOvershootDone) {
                sOvershootDone = true;
            }
            return;
        }
        float step = Math.max(-MAX_STEP, Math.min(MAX_STEP, delta * 0.42f));
        sPosition += step;
        if (Math.abs(target - sPosition) < 0.01f) {
            sPosition = target;
            sOvershootDone = true;
        }
        invalidateDrawers();
    }

    /**
     * Where the open pill starts sliding toward, in columns.
     *
     * <p>The pill travels along the row's columns while the bars underneath are
     * already animated by the droplet's springs, so both have to run off the
     * same clock: the droplet is sized and placed from {@link #slideLeft()} on
     * every frame the slide is in flight, and would otherwise lag a whole
     * column behind the pill it is supposed to be.
     */
    static float slidePosition() {
        return sPosition;
    }

    /** Rounding of {@link #slidePosition()} folded into the droplet's fingerprint. */
    static int slideSteps() {
        return sPosition < 0f ? -1 : Math.round(sPosition * 4f);
    }

    /**
     * Left edge of the open pill as it slides, relative to the row's first
     * column — the same origin the drag controller positions from.
     *
     * <p>At rest this lands exactly on the selected column's {@link #openLeft};
     * mid-slide it is the interpolation between two of those.
     */
    static int slideLeft() {
        if (sColumnWidth <= 0) {
            return 0;
        }
        int open = openWidth(sColumnWidth, true);
        int extra = Math.max(0, open - sColumnWidth);
        float left = sPosition * sColumnWidth - extra * 0.5f;
        float max = Math.max(0, sColumnWidth * sCount - open);
        return Math.round(Math.max(0f, Math.min(left, max)));
    }

    /* ---------------- per-tab geometry ---------------- */

    /**
     * Width of the open pill for a column of {@code tabWidth}.
     *
     * <p>It does not change with the selection: the pill keeps the same width
     * and slides, so the capsule itself never grows or shrinks.
     */
    static int openWidth(int tabWidth, boolean selected) {
        if (!selected || sExpansionPx <= 0) {
            return 0;
        }
        return tabWidth + sExpansionPx;
    }

    /** Left edge of the open pill for a column of this width, in row space. */
    private static int openLeft(int columnLeft, int tabWidth, int openWidth) {
        // The row itself is pinned to exactly n columns, so the pill has to fit
        // inside that box: the spare room is shared by the two sides, and the
        // clamps below only matter at the two ends.
        int extra = Math.max(0, openWidth - tabWidth);
        int centred = columnLeft - extra / 2;
        return Math.max(0, Math.min(centred, sColumnWidth * sCount - openWidth));
    }


    /* ---------------- drawing ---------------- */

    /** True once an install has published a geometry, i.e. the style is live. */
    static boolean isActive() {
        return sColumnWidth > 0;
    }

    /** Repaints the bar for {@code tabRow}; null repaints every installed one. */
    static void refresh(ViewGroup tabRow) {
        ViewGroup row = tabRow != null ? tabRow : LiquidGlassInstaller.currentTabRow();
        if (row == null) {
            return;
        }
        row.invalidate();
        /* The pill is drawn by the bar, so that layer needs the frame too. */
        try {
            ViewParentInvalidator.invalidateParent(row);
        } catch (Throwable ignored) {
        }
    }

    private static void invalidateDrawers() {
        refresh(null);
    }

    /**
     * Paints the drawer's open pill and the selected tab's copy into it.
     *
     * @return true when something was drawn, false when the drawer is not in
     *     the state for this row (or the style is not the drawer at all)
     */
    static boolean draw(ViewGroup tabRow, Canvas canvas, float density) {
        if (sColumnWidth <= 0 || sSelected < 0 || tabRow == null) {
            return false;
        }
        View tab = TabBarBridge.tabAt(tabRow, sSelected);
        if (tab == null || tab.getWidth() <= 0 || tabRow.getChildCount() == 0) {
            return false;
        }
        // Only slots the app laid out left to right are trusted; anything else
        // (a hidden placeholder shifting the mapping, a row still being rebuilt)
        // leaves the drawer idle and the bar showing the plain style underneath,
        // which is the honest state until the next layout pass.
        if (tab.getWidth() != sColumnWidth) {
            return false;
        }
        // While the pill slides it is in two slots at once, so it is drawn
        // sliding and each slot draws its own tab copy inside it where it is.
        boolean sliding = Math.abs(sPosition - sSelected) > 0.001f;
        if (sliding) {
            return drawSliding(tabRow, canvas, density);
        }
        int open = openWidth(tab.getWidth(), true);
        int left = openLeft(tab.getLeft(), tab.getWidth(), open);
        int top = tab.getTop();
        int bottom = tab.getBottom();
        if (bottom <= top || open <= 0) {
            return false;
        }
        float radius = (bottom - top) * 0.5f;
        sPillPaint.setColor(sNight ? PILL_DARK : PILL_LIGHT);
        canvas.drawRoundRect(left, top, left + open, bottom, radius, radius, sPillPaint);


        // The app's selected tab, slid into the middle of the open pill. It is
        // drawn, never moved: the real tab keeps its own place, its own touch
        // target and its own selected state.
        int dx = left + (open - tab.getWidth()) / 2 - tab.getLeft();
        sClip.reset();
        sClip.addRoundRect(left, top, left + open, bottom, radius, radius,
                Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(sClip);
        canvas.translate(dx, 0f);
        tab.draw(canvas);
        canvas.restoreToCount(save);
        drawLabel(tab, canvas, density, left, open);
        return true;
    }

    /**
     * The mid-slide frame: one pill spanning both slots, with the tab copies
     * cross-fading into it.
     *
     * <p>The pill's width during the slide is the interpolation between a
     * closed slot and the open pill — so it reads as the drawer stretching out
     * of the slot it left and back into the one it is arriving at, which is
     * what a drawer does.
     */
    private static boolean drawSliding(ViewGroup tabRow, Canvas canvas,
                                       float density) {
        // Slots are addressed by visible position everywhere in this module
        // (TabBarBridge.tabAt), so the first and last laid-out ones are used
        // rather than children 0 and n-1, which a hidden placeholder can
        // displace.
        View first = TabBarBridge.tabAt(tabRow, 0);
        if (first == null) {
            return false;
        }
        int top = first.getTop();
        int bottom = first.getBottom();
        if (bottom <= top) {
            return false;
        }
        // Fractions of the way from column i to column i+1.
        float lo = Math.max(0f, Math.min(sPosition, Math.max(0, sCount - 1)));
        float hi = lo + 1f;
        float f = lo - (float) Math.floor(lo);
        // Ease so the pill spends more time near its two ends than in between.
        float eased = f * f * (3f - 2f * f);
        // Open at both ends, closed (a plain slot) in the middle of the travel.
        float openness = Math.abs(2f * eased - 1f);
        int closedWidth = sColumnWidth;
        int openFull = openWidth(closedWidth, true);
        int width = Math.round(closedWidth + (openFull - closedWidth) * openness);
        int rowWidth = sColumnWidth * sCount;
        float centre = (lo + eased) * sColumnWidth + sColumnWidth * 0.5f;
        int left = Math.round(centre - width * 0.5f);
        left = Math.max(0, Math.min(left, rowWidth - width));
        float radius = (bottom - top) * 0.5f;
        sPillPaint.setColor(sNight ? PILL_DARK : PILL_LIGHT);
        canvas.drawRoundRect(left, top, left + width, bottom, radius, radius, sPillPaint);

        int save = canvas.save();
        sClip.reset();
        sClip.addRoundRect(left, top, left + width, bottom, radius, radius,
                Path.Direction.CW);
        canvas.clipPath(sClip);
        int ia = (int) lo;
        int ib = Math.min(sCount - 1, (int) hi);
        drawSlotCopy(tabRow, ia, canvas, 1f - eased, left, width);
        if (ib != ia) {
            drawSlotCopy(tabRow, ib, canvas, eased, left, width);
        }
        canvas.restoreToCount(save);
        // The label only reads at either end of the travel.
        if (openness > 0.75f) {
            int slot = eased < 0.5f ? ia : ib;
            View tab = TabBarBridge.tabAt(tabRow, slot);
            if (tab != null) {
                drawLabel(tab, canvas, density, left, width);
            }
        }
        return true;
    }

    /** Draws one slot's tab inside the sliding pill at {@code alpha}. */
    private static void drawSlotCopy(ViewGroup tabRow, int slot, Canvas canvas,
                                     float alpha, int pillLeft, int pillWidth) {
        if (alpha <= 0.02f) {
            return;
        }
        View tab = TabBarBridge.tabAt(tabRow, slot);
        if (tab == null || tab.getWidth() <= 0) {
            return;
        }
        int shift = pillLeft + (pillWidth - tab.getWidth()) / 2 - tab.getLeft();
        int save = canvas.save();
        canvas.translate(shift, 0f);
        if (alpha < 0.98f) {
            // saveLayerAlpha rather than setting alpha on anything of ours: the
            // copies are the app's own views, drawn through the app's own
            // paint, and this is the only alpha that belongs to us.
            int layer = canvas.saveLayerAlpha(0f, 0f, tabRow.getWidth(),
                    tabRow.getHeight(), Math.round(255 * alpha));
            tab.draw(canvas);
            canvas.restoreToCount(layer);
        } else {
            tab.draw(canvas);
        }
        canvas.restoreToCount(save);
    }

    /**
     * Draws the selected tab's label next to its icon.
     *
     * <p>The label is the app's own {@code TextView}, drawn again in its own
     * colour at the height it already occupies — so a theme switch, a font
     * scale or a renamed tab come through without the module knowing anything
     * about them. The copy inside the pill is the only one visible: the real
     * one is covered by the pill surface, which is what lets the bar stay at
     * icon-per-column width.
     */
    private static void drawLabel(View tab, Canvas canvas,
                                  float density, int pillLeft, int open) {
        View icon = findIcon(tab);
        android.widget.TextView label = findLabel(tab);
        if (icon == null || label == null
                || label.getWidth() <= 0 || label.getHeight() <= 0) {
            return;
        }
        CharSequence text = label.getText();
        if (text == null || text.length() == 0) {
            return;
        }
        int gap = Math.round(density * 6f);
        int pillRight = pillLeft + open - Math.round(density * 12f);
        int textWidth = Math.round(label.getPaint().measureText(text, 0, text.length()));
        if (textWidth <= 0) {
            return;
        }
        // Where the icon ends in the pill — the icon itself is drawn by the
        // tab copy above, shifted by the same amount.
        int shift = pillLeft + (open - tab.getWidth()) / 2 - tab.getLeft();
        int iconRight = icon.getRight() + tab.getLeft() + shift;
        int start = iconRight + gap;
        if (start + textWidth > pillRight) {
            return; // no room for this label at this capsule width
        }
        int y = tab.getTop() + (tab.getHeight() - label.getHeight()) / 2;
        int save = canvas.save();
        canvas.translate(start - label.getLeft(), y - label.getTop());
        label.draw(canvas);
        canvas.restoreToCount(save);
    }

    /**
     * The icon view inside a tab, found the same way the droplet finds badges:
     * by the host's class-name suffix, which survives obfuscation where the
     * package does not.
     */
    private static View findIcon(View v) {
        HostApp app = LiquidGlassModule.app();
        if (app != null && app.isTabIconClass(v.getClass().getName())) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findIcon(g.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The chosen tab's own title, excluding unread badges.
     *
     * <p>Preferred over a walk: {@link LiquidGlassInstaller#findTabTitle} is
     * the same "not a badge, has text" rule the rest of the module uses, and it
     * picks QQ's tagged {@code QUIBlendTextView} over a red dot's count.
     */
    private static android.widget.TextView findLabel(View tab) {
        return LiquidGlassInstaller.findTabTitle(tab);
    }

    /** Small helper so a renamed or absent row does not throw on a per-frame path. */
    private static final class ViewParentInvalidator {
        static void invalidateParent(View v) {
            try {
                android.view.ViewParent p = v.getParent();
                if (p instanceof View) {
                    ((View) p).invalidate();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
