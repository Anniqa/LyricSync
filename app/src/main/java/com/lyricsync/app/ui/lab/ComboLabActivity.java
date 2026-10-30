package com.lyricsync.app.ui.lab;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.LayoutInflater;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.lyricsync.app.R;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.renderer.AnimConfig;
import com.lyricsync.app.renderer.AnimStyleDef;
import com.lyricsync.app.renderer.ComboFusion;
import com.lyricsync.app.renderer.SyllableHighlighter;
import com.lyricsync.app.ui.AndroidPrefs;
import com.lyricsync.app.ui.AnimSelection;
import com.lyricsync.app.util.AppFont;
import com.lyricsync.app.util.Haptics;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Combo Lab: the motion × effect picker as a small fusion game. Drop a motion and an
 * effect into the two slots, press FUSE, and a result card reveals the fused combo
 * (name, elements, rarity, power) with a live lyric preview. Every new pair goes into
 * the collection ("ComboDex"); "Equip" makes it the lyric style. The selection keys
 * are the same {@code lyric_anim_motion}/{@code lyric_anim_effect} prefs.
 */
public final class ComboLabActivity extends Activity {

    static final String PREF_DISCOVERED = "pref_combo_discovered";
    private static final long PREVIEW_LINE_MS = 2600, PREVIEW_PAUSE_MS = 1400;
    /** Preview font: large enough to show the effect, small enough that the line fits on one row. */
    private static final float PREVIEW_SP = 24f;

    private SharedPreferences prefs;
    private AnimStyleDef motion, effect;
    private Set<String> discovered;
    private boolean pickingMotion = true;

    private TextView dex, rarity, resultName, typeMotion, typeEffect, recipe, powerValue, isNew, pickerHint;
    private View slotMotion, slotEffect, synergy, fuse, flash;
    private LinearLayout card, stars;
    private LinearProgressIndicator dexBar, powerBar;
    private GridLayout grid;
    private FrameLayout previewBox;

    private SyllableHighlighter previewHighlighter;
    private long previewStart, lastFrame;
    private final Choreographer.FrameCallback previewFrame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            if (previewHighlighter != null) {
                long t = (now / 1_000_000L - previewStart) % (PREVIEW_LINE_MS + PREVIEW_PAUSE_MS);
                double dt = lastFrame == 0 ? 1 / 60.0 : Math.min(0.1, (now - lastFrame) / 1e9);
                previewHighlighter.updateHighlight(t, dt);
            }
            lastFrame = now;
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public static void open(Activity from) {
        from.startActivity(new Intent(from, ComboLabActivity.class));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_combo_lab);
        prefs = getSharedPreferences("lyricsync", MODE_PRIVATE);
        AndroidPrefs ap = new AndroidPrefs(prefs);
        motion = AnimStyleDef.Registry.motionByKey(AnimSelection.motionKey(ap));
        effect = AnimStyleDef.Registry.effectByKey(AnimSelection.effectKey(ap));
        discovered = new HashSet<>(prefs.getStringSet(PREF_DISCOVERED, new HashSet<>()));
        discovered.add(ComboFusion.key(motion, effect));

        dex = findViewById(R.id.lab_dex);
        dexBar = findViewById(R.id.lab_dex_bar);
        rarity = findViewById(R.id.lab_rarity);
        stars = findViewById(R.id.lab_stars);
        resultName = findViewById(R.id.lab_result_name);
        typeMotion = findViewById(R.id.lab_type_motion);
        typeEffect = findViewById(R.id.lab_type_effect);
        recipe = findViewById(R.id.lab_recipe);
        powerBar = findViewById(R.id.lab_power_bar);
        powerValue = findViewById(R.id.lab_power_value);
        isNew = findViewById(R.id.lab_new);
        synergy = findViewById(R.id.lab_synergy);
        pickerHint = findViewById(R.id.lab_picker_hint);
        slotMotion = findViewById(R.id.lab_slot_motion);
        slotEffect = findViewById(R.id.lab_slot_effect);
        ((TextView) slotMotion.findViewById(R.id.slot_label)).setText(R.string.lab_tab_motion);
        ((TextView) slotEffect.findViewById(R.id.slot_label)).setText(R.string.lab_tab_effect);
        card = findViewById(R.id.lab_card);
        grid = findViewById(R.id.lab_grid);
        previewBox = findViewById(R.id.lab_preview_box);
        fuse = findViewById(R.id.lab_fuse);
        flash = findViewById(R.id.lab_flash);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.lab_root), (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            View content = findViewById(R.id.lab_content);
            content.setPadding(content.getPaddingLeft(), bars.top, content.getPaddingRight(),
                    bars.bottom + Math.round(32 * density()));
            return WindowInsetsCompat.CONSUMED;
        });

        findViewById(R.id.lab_back).setOnClickListener(v -> finish());
        slotMotion.setOnClickListener(v -> selectPicker(true));
        slotEffect.setOnClickListener(v -> selectPicker(false));
        MaterialButtonToggleGroup tabs = findViewById(R.id.lab_picker_tabs);
        tabs.check(R.id.lab_tab_motion);
        tabs.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) selectPicker(id == R.id.lab_tab_motion);
        });
        fuse.setOnClickListener(v -> fuse(true));
        findViewById(R.id.lab_random).setOnClickListener(v -> {
            Haptics.tick(v);
            Random r = new Random();
            List<AnimStyleDef> ms = AnimStyleDef.Registry.motionStyles(), es = AnimStyleDef.Registry.effectStyles();
            motion = ms.get(r.nextInt(ms.size()));
            effect = es.get(1 + r.nextInt(es.size() - 1));
            fuse(true);
        });
        findViewById(R.id.lab_equip).setOnClickListener(v -> {
            Haptics.confirm(v);
            AnimSelection.save(new AndroidPrefs(prefs), motion.key, effect.key);
            Toast.makeText(this, getString(R.string.lab_equipped, ComboFusion.name(motion, effect)), Toast.LENGTH_SHORT).show();
            pulse(card);
        });

        renderSlots();
        renderPicker();
        fuse(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(previewFrame);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Choreographer.getInstance().removeFrameCallback(previewFrame);
        prefs.edit().putStringSet(PREF_DISCOVERED, new HashSet<>(discovered)).apply();
    }

    // ── Slots & picker ───────────────────────────────────────────────────

    private void selectPicker(boolean motionSide) {
        pickingMotion = motionSide;
        MaterialButtonToggleGroup tabs = findViewById(R.id.lab_picker_tabs);
        int id = motionSide ? R.id.lab_tab_motion : R.id.lab_tab_effect;
        if (tabs.getCheckedButtonId() != id) tabs.check(id);
        pickerHint.setText(motionSide ? R.string.lab_pick_motion : R.string.lab_pick_effect);
        renderSlots();
        renderPicker();
    }

    private void renderSlots() {
        styleSlot(slotMotion, motion, pickingMotion);
        styleSlot(slotEffect, effect, !pickingMotion);
    }

    /** Element badge + name; the focused slot gets a solid element-coloured rim. */
    private void styleSlot(View slot, AnimStyleDef def, boolean focused) {
        ComboFusion.Element el = ComboFusion.element(def);
        ((TextView) slot.findViewById(R.id.slot_name)).setText(def.displayName);
        TextView element = slot.findViewById(R.id.slot_element);
        element.setText(elementLabel(el));
        element.setTextColor(el.color);
        TextView label = slot.findViewById(R.id.slot_label);
        label.setTextColor(focused ? el.color : 0xFFA9ACB8);
        slot.findViewById(R.id.slot_badge).setBackgroundTintList(ColorStateList.valueOf(withAlpha(el.color, 0x33)));
        ImageView icon = slot.findViewById(R.id.slot_icon);
        icon.setImageResource(elementIcon(el));
        icon.setImageTintList(ColorStateList.valueOf(el.color));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(24 * density());
        bg.setColor(focused ? withAlpha(el.color, 0x1F) : 0xFF15161D);
        if (focused) bg.setStroke(Math.round(2 * density()), el.color);
        else bg.setStroke(Math.round(1.5f * density()), 0xFF3A3D4A, 6 * density(), 4 * density());
        slot.setBackground(bg);
        slot.setContentDescription(getString(R.string.lab_slot_cd,
                getString(slot == slotMotion ? R.string.lab_tab_motion : R.string.lab_tab_effect), def.displayName));
    }

    /** Grid of choices for the focused slot; a dot marks pairs not yet in the collection. */
    private void renderPicker() {
        grid.removeAllViews();
        List<AnimStyleDef> list = pickingMotion ? AnimStyleDef.Registry.motionStyles() : AnimStyleDef.Registry.effectStyles();
        int gap = Math.round(4 * density());
        int cell = (grid.getWidth() > 0 ? grid.getWidth()
                : getResources().getDisplayMetrics().widthPixels - Math.round(32 * density())) / 3;
        LayoutInflater inflater = LayoutInflater.from(this);
        for (AnimStyleDef d : list) {
            boolean chosen = d == (pickingMotion ? motion : effect);
            ComboFusion.Element el = ComboFusion.element(d);
            boolean known = discovered.contains(pickingMotion ? ComboFusion.key(d, effect) : ComboFusion.key(motion, d));
            View item = inflater.inflate(R.layout.item_lab_choice, grid, false);
            TextView name = item.findViewById(R.id.choice_name);
            name.setText(d.displayName);
            name.setTextColor(chosen ? 0xFF04210F : 0xFFF2F3F7);
            item.findViewById(R.id.choice_element).setBackgroundTintList(
                    ColorStateList.valueOf(chosen ? 0xFF04210F : el.color));
            ImageView combo = item.findViewById(R.id.choice_combo);
            combo.setVisibility(d.isCombo() ? View.VISIBLE : View.GONE);
            combo.setImageTintList(ColorStateList.valueOf(chosen ? 0xFF04210F : 0xFFFFB547));
            item.findViewById(R.id.choice_new).setVisibility(known ? View.GONE : View.VISIBLE);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(16 * density());
            bg.setColor(chosen ? el.color : 0xFF1C1D26);
            bg.setStroke(Math.round(1 * density()), chosen ? el.color : 0xFF262833);
            item.setBackground(bg);
            item.setContentDescription(d.displayName);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell - 2 * gap;
            lp.height = Math.round(64 * density());
            lp.setMargins(gap, gap, gap, gap);
            item.setLayoutParams(lp);
            item.setOnClickListener(v -> {
                Haptics.tick(v);
                pulse(v);
                if (pickingMotion) motion = d;
                else effect = d;
                renderSlots();
                renderPicker();
                // After the motion, hand the player straight to the effect slot.
                if (pickingMotion) v.postDelayed(() -> selectPicker(false), 180);
            });
            grid.addView(item);
        }
    }

    // ── Fusion ───────────────────────────────────────────────────────────

    /** Animates the two slots into the button, flashes, and reveals the result card. */
    private void fuse(boolean animate) {
        ComboFusion.Result r = ComboFusion.fuse(motion, effect);
        boolean fresh = discovered.add(ComboFusion.key(motion, effect));
        renderSlots();
        if (!animate) {
            showResult(r, false);
            return;
        }
        Haptics.confirm(fuse);
        float pull = slotEffect.getX() - slotMotion.getX();
        AnimatorSet charge = new AnimatorSet();
        charge.playTogether(
                ObjectAnimator.ofFloat(slotMotion, View.TRANSLATION_X, 0f, pull * 0.25f),
                ObjectAnimator.ofFloat(slotEffect, View.TRANSLATION_X, 0f, -pull * 0.25f),
                ObjectAnimator.ofFloat(slotMotion, View.ROTATION, 0f, 6f),
                ObjectAnimator.ofFloat(slotEffect, View.ROTATION, 0f, -6f),
                ObjectAnimator.ofFloat(fuse, View.SCALE_X, 1f, 1.25f),
                ObjectAnimator.ofFloat(fuse, View.SCALE_Y, 1f, 1.25f),
                ObjectAnimator.ofFloat(fuse, View.ROTATION, 0f, 180f));
        charge.setDuration(260);
        charge.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                flash.setAlpha(0.85f);
                flash.animate().alpha(0f).setDuration(380).start();
                for (View v : new View[]{slotMotion, slotEffect}) {
                    v.animate().translationX(0f).rotation(0f).setDuration(320)
                            .setInterpolator(new OvershootInterpolator(2f)).start();
                }
                fuse.animate().scaleX(1f).scaleY(1f).rotation(360f).setDuration(320).withEndAction(() -> fuse.setRotation(0f)).start();
                showResult(r, fresh);
            }
        });
        charge.start();
    }

    private void showResult(ComboFusion.Result r, boolean fresh) {
        renderStars(r.rarity);
        rarity.setText(getString(rarityLabel(r.rarity)).toUpperCase(java.util.Locale.getDefault()));
        rarity.setTextColor(r.rarity.color);
        resultName.setText(r.name);
        styleType(typeMotion, r.motionElement);
        styleType(typeEffect, r.effectElement);
        recipe.setText(getString(R.string.lab_recipe, motion.displayName, effect.displayName));
        isNew.setVisibility(fresh ? View.VISIBLE : View.GONE);
        synergy.setVisibility(r.synergy ? View.VISIBLE : View.GONE);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{withAlpha(r.rarity.color, 0x24), 0xFF0E0F15});
        bg.setCornerRadius(28 * density());
        bg.setStroke(Math.round(1.5f * density()), withAlpha(r.rarity.color, 0xCC));
        card.setBackground(bg);
        powerBar.setIndicatorColor(r.rarity.color);
        ValueAnimator count = ValueAnimator.ofInt(0, r.power);
        count.setDuration(700);
        count.addUpdateListener(a -> {
            int v = (int) a.getAnimatedValue();
            powerBar.setProgressCompat(v, false);
            powerValue.setText(String.valueOf(v));
        });
        count.start();
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.setAlpha(0.4f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(360)
                .setInterpolator(new OvershootInterpolator(1.4f)).start();
        int found = Math.min(discovered.size(), ComboFusion.totalCombos());
        dex.setText(getString(R.string.lab_dex, found, ComboFusion.totalCombos()));
        dexBar.setMax(ComboFusion.totalCombos());
        dexBar.setProgressCompat(found, true);
        buildPreview(AnimConfig.combine(motion, effect));
        renderPicker();
    }

    /** Five vector stars: filled in the rarity colour, the rest outlined grey. */
    private void renderStars(ComboFusion.Rarity r) {
        stars.removeAllViews();
        int size = Math.round(16 * density());
        for (int i = 0; i < 5; i++) {
            ImageView star = new ImageView(this);
            star.setImageResource(R.drawable.ls_ic_star);
            star.setImageTintList(ColorStateList.valueOf(i < r.stars ? r.color : 0xFF3A3D4A));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.setMarginStart(Math.round(2 * density()));
            stars.addView(star, lp);
        }
        stars.setContentDescription(getString(R.string.lab_stars_cd, r.stars));
    }

    private void styleType(TextView chip, ComboFusion.Element el) {
        chip.setText(elementLabel(el));
        chip.setTextColor(el.color);
        chip.setBackgroundTintList(ColorStateList.valueOf(withAlpha(el.color, 0x26)));
    }

    /** A looping lyric line rendered by the real highlighter, centred in the stage. */
    private void buildPreview(AnimConfig cfg) {
        AppFont.FontPair fonts = AppFont.current(this);
        previewHighlighter = new SyllableHighlighter(this, fonts.bold, fonts.medium, PREVIEW_SP);
        previewHighlighter.setAnimConfig(cfg);
        previewHighlighter.setSyllableMode(true);
        previewHighlighter.setCenterLines(true);
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, PREVIEW_LINE_MS, getString(R.string.lab_preview_line));
        String[] words = line.text.split(" ");
        long per = PREVIEW_LINE_MS / Math.max(1, words.length);
        for (int i = 0; i < words.length; i++) {
            line.words.add(new LyricsData.Word(i * per, (i + 1) * per, words[i]));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        SyllableHighlighter.LineView lv = previewHighlighter.createLineView(line, lp);
        previewBox.removeAllViews();
        previewBox.addView(lv.rootView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        previewStart = System.nanoTime() / 1_000_000L;
    }

    private static int rarityLabel(ComboFusion.Rarity r) {
        switch (r) {
            case MYTHIC: return R.string.lab_rarity_mythic;
            case LEGENDARY: return R.string.lab_rarity_legendary;
            case EPIC: return R.string.lab_rarity_epic;
            case RARE: return R.string.lab_rarity_rare;
            default: return R.string.lab_rarity_common;
        }
    }

    private String elementLabel(ComboFusion.Element el) {
        switch (el) {
            case FIRE: return getString(R.string.lab_el_fire);
            case WATER: return getString(R.string.lab_el_water);
            case ELECTRIC: return getString(R.string.lab_el_electric);
            case COSMIC: return getString(R.string.lab_el_cosmic);
            case NATURE: return getString(R.string.lab_el_nature);
            case SPIRIT: return getString(R.string.lab_el_spirit);
            case NEON: return getString(R.string.lab_el_neon);
            case STEEL: return getString(R.string.lab_el_steel);
            default: return getString(R.string.lab_el_normal);
        }
    }

    private static int elementIcon(ComboFusion.Element el) {
        switch (el) {
            case FIRE: return R.drawable.ls_el_fire;
            case WATER: return R.drawable.ls_el_water;
            case ELECTRIC: return R.drawable.ic_bolt;
            case COSMIC: return R.drawable.ls_el_cosmic;
            case NATURE: return R.drawable.ls_el_nature;
            case SPIRIT: return R.drawable.ic_sparkles;
            case NEON: return R.drawable.ls_el_neon;
            case STEEL: return R.drawable.ls_el_steel;
            default: return R.drawable.ls_el_normal;
        }
    }

    private static void pulse(View v) {
        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new OvershootInterpolator(3f)).start()).start();
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
