package com.crmsistems.app.ui.splash;

import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.MainActivity;

/**
 * Заставка с неоновой вывеской. Показывается около трёх секунд,
 * тапом по экрану можно пропустить.
 */
public class SplashActivity extends AppCompatActivity {

    private static final long DURATION = 2900L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean launched;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        NeonSignView sign = new NeonSignView(this);
        sign.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sign.setOnClickListener(v -> openMain());
        setContentView(sign);

        handler.postDelayed(this::openMain, DURATION);
    }

    private void openMain() {
        if (launched) return;
        launched = true;
        Bundle animation = ActivityOptions.makeCustomAnimation(this,
                android.R.anim.fade_in, android.R.anim.fade_out).toBundle();
        startActivity(new Intent(this, MainActivity.class), animation);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
