package com.oculus.twilight.vrbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class VrBoxPairingOverlay {
    private static final String PREFS = "vrbox_pairing_bridge";
    private static final String KEY_PAIRED = "paired";
    private static final long RETRY_MS = 400L;
    private static final int MAX_TRIES = 35;
    private static final Set<Activity> injected = Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private static boolean installed;

    private VrBoxPairingOverlay() {}

    public static synchronized void install(Application app) {
        if (installed || app == null) return;
        installed = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) { schedule(activity, 0); }
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {
                synchronized (injected) { injected.remove(activity); }
            }
        });
    }

    private static void schedule(final Activity activity, final int attempt) {
        if (activity == null || activity.isFinishing()) return;
        if (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return;
        synchronized (injected) { if (injected.contains(activity)) return; }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                if (activity.isFinishing()) return;
                if (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return;
                if (tryInject(activity)) {
                    synchronized (injected) { injected.add(activity); }
                } else if (attempt < MAX_TRIES) {
                    schedule(activity, attempt + 1);
                }
            }
        }, attempt == 0 ? 250L : RETRY_MS);
    }

    private static boolean tryInject(final Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return false;
        View decor = window.getDecorView();
        if (!(decor instanceof ViewGroup)) return false;

        View title = findText((ViewGroup) decor, "parear um novo headset", "pair a new headset");
        if (title == null) return false;

        final ViewGroup overlayHost = activity.findViewById(android.R.id.content);
        if (overlayHost == null) return false;
        if (overlayHost.findViewWithTag("vrbox_pairing_card") != null) return true;

        final Context context = activity;
        final int screenW = context.getResources().getDisplayMetrics().widthPixels;
        final int side = dp(context, 20);
        final int cardH = dp(context, 218);

        final FrameLayout card = new FrameLayout(context);
        card.setTag("vrbox_pairing_card");
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription("VR Box");

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.rgb(245, 245, 247));
        cardBg.setCornerRadius(dp(context, 18));
        card.setBackground(cardBg);
        if (android.os.Build.VERSION.SDK_INT >= 21) card.setElevation(dp(context, 2));

        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 48));
        InputStream in = null;
        try {
            in = context.getAssets().open("vrbox_headset.webp");
            Bitmap bmp = BitmapFactory.decodeStream(in);
            image.setImageBitmap(bmp);
        } catch (Throwable ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
        card.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final TextView label = new TextView(context);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        label.setTextColor(Color.WHITE);
        label.setTextSize(17f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        GradientDrawable stripBg = new GradientDrawable();
        stripBg.setColor(Color.rgb(166, 166, 171));
        float r = dp(context, 18);
        stripBg.setCornerRadii(new float[]{0,0,0,0,r,r,r,r});
        label.setBackground(stripBg);
        card.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 46), Gravity.BOTTOM));
        refreshLabel(context, label);

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                if (!prefs.getBoolean(KEY_PAIRED, false)) {
                    prefs.edit().putBoolean(KEY_PAIRED, true).apply();
                    refreshLabel(context, label);
                    Toast.makeText(context, "VR Box pareado", Toast.LENGTH_SHORT).show();
                    try {
                        new AlertDialog.Builder(context)
                                .setTitle("VR Box pareado")
                                .setMessage("O perfil VR Box foi ativado neste aparelho e agora aparece como pareado dentro do aplicativo.")
                                .setPositiveButton("Continuar", null)
                                .show();
                    } catch (Throwable ignored) {}
                } else {
                    try {
                        new AlertDialog.Builder(context)
                                .setTitle("VR Box já está pareado")
                                .setMessage("O perfil VR Box continua ativo neste aparelho.")
                                .setPositiveButton("OK", null)
                                .setNegativeButton("Desparear", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface dialog, int which) {
                                        prefs.edit().putBoolean(KEY_PAIRED, false).apply();
                                        refreshLabel(context, label);
                                    }
                                }).show();
                    } catch (Throwable ignored) {}
                }
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(dp(context, 240), screenW - side * 2), cardH, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.leftMargin = side;
        lp.rightMargin = side;
        lp.bottomMargin = dp(context, 105);
        overlayHost.addView(card, lp);

        final View notNow = findText((ViewGroup) decor, "agora não", "not now");
        if (notNow != null) {
            notNow.post(new Runnable() {
                @Override public void run() {
                    try {
                        int[] pos = new int[2];
                        notNow.getLocationOnScreen(pos);
                        int screenH = context.getResources().getDisplayMetrics().heightPixels;
                        int desiredBottom = Math.max(dp(context, 84), screenH - pos[1] + dp(context, 14));
                        ViewGroup.LayoutParams base = card.getLayoutParams();
                        if (base instanceof FrameLayout.LayoutParams) {
                            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) base;
                            flp.bottomMargin = desiredBottom;
                            card.setLayoutParams(flp);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        }
        return true;
    }

    private static void refreshLabel(Context context, TextView label) {
        boolean paired = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAIRED, false);
        label.setText(paired ? "VR Box   •   Pareado ✓" : "VR Box");
    }

    private static View findText(ViewGroup root, String... needles) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextView) {
                CharSequence cs = ((TextView) child).getText();
                String txt = normalize(cs == null ? "" : cs.toString());
                for (String needle : needles) if (txt.contains(normalize(needle))) return child;
            }
            if (child instanceof ViewGroup) {
                View found = findText((ViewGroup) child, needles);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String normalize(String s) {
        try {
            String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
            return n.toLowerCase(java.util.Locale.ROOT).trim();
        } catch (Throwable ignored) {
            return s == null ? "" : s.toLowerCase().trim();
        }
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
