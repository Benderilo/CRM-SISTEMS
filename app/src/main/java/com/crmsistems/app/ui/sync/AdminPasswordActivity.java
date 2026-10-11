package com.crmsistems.app.ui.sync;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.databinding.ActivityAdminPasswordBinding;
import com.crmsistems.app.net.ServerApi;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Ui;

import org.json.JSONObject;

/**
 * Смена логина и пароля от панели управления сайтом — прямо с телефона владельца.
 */
public class AdminPasswordActivity extends AppCompatActivity {

    private ActivityAdminPasswordBinding b;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityAdminPasswordBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.scroll);

        b.appbarInc.toolbar.setTitle(R.string.admin_pwd_title);
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());

        b.save.setOnClickListener(v -> submit());
        loadCurrentLogin();
    }

    /** Подставляем текущий логин, чтобы его не пришлось вспоминать. */
    private void loadCurrentLogin() {
        if (!Prefs.serverReady(this)) {
            Ui.snack(b.getRoot(), getString(R.string.server_not_configured));
            return;
        }
        ServerApi api = new ServerApi(this);
        Repository.get(this).io().execute(() -> {
            String login = null;
            try {
                JSONObject a = api.adminInfo().getJSONObject("admin");
                login = a.optString("login", "");
            } catch (Exception ignored) {
                // Не смогли — пользователь введёт логин руками.
            }
            final String value = login;
            runOnUiThread(() -> {
                if (value != null && b.login.getText() != null
                        && b.login.getText().length() == 0) {
                    b.login.setText(value);
                }
            });
        });
    }

    private void submit() {
        String login = Ui.text(b.login);
        String oldPassword = Ui.text(b.oldPassword);
        String newPassword = Ui.text(b.newPassword);
        String repeat = Ui.text(b.newPassword2);

        if (oldPassword.isEmpty()) {
            Ui.snack(b.getRoot(), getString(R.string.admin_pwd_need_old));
            return;
        }
        if (newPassword.length() < 8) {
            Ui.snack(b.getRoot(), getString(R.string.admin_pwd_short));
            return;
        }
        if (!newPassword.equals(repeat)) {
            Ui.snack(b.getRoot(), getString(R.string.admin_pwd_mismatch));
            return;
        }
        if (!Prefs.serverReady(this)) {
            Ui.snack(b.getRoot(), getString(R.string.server_not_configured));
            return;
        }

        b.progress.setVisibility(View.VISIBLE);
        b.save.setEnabled(false);

        ServerApi api = new ServerApi(this);
        Repository.get(this).io().execute(() -> {
            String error = null;
            try {
                api.changeAdminPassword(login, oldPassword, newPassword);
            } catch (ServerApi.ApiException e) {
                error = e.getMessage();
            }
            final String err = error;
            runOnUiThread(() -> {
                b.progress.setVisibility(View.GONE);
                b.save.setEnabled(true);
                if (err == null) {
                    Ui.snack(b.getRoot(), getString(R.string.admin_pwd_done));
                    b.oldPassword.setText("");
                    b.newPassword.setText("");
                    b.newPassword2.setText("");
                } else {
                    Ui.snack(b.getRoot(), err);
                }
            });
        });
    }
}
