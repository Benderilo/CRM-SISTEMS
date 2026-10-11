package com.crmsistems.app.ui.common;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.crmsistems.app.R;
import com.crmsistems.app.data.entity.Client;

/**
 * Цвет карточки клиента по сегменту — бледная заливка и текст к ней.
 * <p>
 * Устроено так же, как {@link StatusColors} у заказов: в списке видно с одного
 * взгляда, кто перед тобой, ещё до чтения подписи. VIP — золотой, новый —
 * синий, постоянный — зелёный, спящий — жёлтый, проблемный — красный.
 * <p>
 * Сегмент хранится строкой ({@link Client#SEGMENTS}), поэтому сверяем по
 * названию: пользователь может завести и свой, для него цвет будет спокойный
 * серый — лучше без цвета, чем чужой цвет.
 * <p>
 * Сами цвета берутся из ресурсов и потому разные в светлой и тёмной теме
 * (values/colors.xml и values-night/colors.xml).
 */
public final class SegmentColors {

    private SegmentColors() {
    }

    /** Заливка карточки. */
    public static int background(Context ctx, String segment) {
        return ContextCompat.getColor(ctx, backgroundRes(segment));
    }

    /** Цвет текста и ярлыка на этой заливке. */
    public static int foreground(Context ctx, String segment) {
        return ContextCompat.getColor(ctx, foregroundRes(segment));
    }

    private static int backgroundRes(String segment) {
        switch (key(segment)) {
            case "новый":
                return R.color.segment_new_bg;
            case "постоянный":
                return R.color.segment_regular_bg;
            case "vip":
                return R.color.segment_vip_bg;
            case "спящий":
                return R.color.segment_sleeping_bg;
            case "проблемный":
                return R.color.segment_problem_bg;
            default:
                return R.color.segment_none_bg;
        }
    }

    private static int foregroundRes(String segment) {
        switch (key(segment)) {
            case "новый":
                return R.color.segment_new_fg;
            case "постоянный":
                return R.color.segment_regular_fg;
            case "vip":
                return R.color.segment_vip_fg;
            case "спящий":
                return R.color.segment_sleeping_fg;
            case "проблемный":
                return R.color.segment_problem_fg;
            default:
                return R.color.segment_none_fg;
        }
    }

    /** Сегмент мог приехать с сайта с другим регистром или пробелами. */
    private static String key(String segment) {
        return segment == null ? "" : segment.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
