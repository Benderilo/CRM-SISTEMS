package com.crmsistems.app.ui.site;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.databinding.ActivitySiteContentBinding;
import com.crmsistems.app.databinding.ItemNewsBinding;
import com.crmsistems.app.net.ServerApi;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Hints;
import com.crmsistems.app.util.Ui;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Контакты и новости сайта. Всё, что покупатель видит в шапке, в контактах
 * и в бегущей строке на главной, правится здесь — лезть на сервер не нужно.
 * <p>
 * Тексты живут только на сайте: экран читает их при открытии и пишет обратно,
 * локальной копии в базе приложения нет — расходиться нечему.
 */
public class SiteContentActivity extends AppCompatActivity {

    private ActivitySiteContentBinding b;
    private final List<JSONObject> news = new ArrayList<>();
    private boolean loaded;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySiteContentBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.scroll);
        Hints.label(b.sectionShop, R.string.help_site_shop);
        Hints.field(b.titleLayout, R.string.help_site_title);
        Hints.field(b.taglineLayout, R.string.help_site_tagline);
        Hints.field(b.heroLayout, R.string.help_site_hero);
        Hints.field(b.phoneLayout, R.string.help_site_phone);
        Hints.field(b.hoursLayout, R.string.help_site_hours);
        Hints.field(b.emailLayout, R.string.help_site_email);
        Hints.field(b.topLeftLayout, R.string.help_site_top);
        Hints.field(b.topRightLayout, R.string.help_site_top);

        b.appbarInc.toolbar.setTitle(R.string.site_title);
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());

        b.save.setOnClickListener(v -> saveTexts());
        b.addNews.setOnClickListener(v -> editNews(null));

        load();
    }

    // ------------------------------------------------------------- загрузка

    private void load() {
        if (!Prefs.serverReady(this)) {
            Ui.snack(b.getRoot(), getString(R.string.server_not_configured));
            return;
        }
        busy(true);
        ServerApi api = new ServerApi(this);
        Repository.get(this).io().execute(() -> {
            try {
                JSONObject j = api.site();
                JSONObject site = j.optJSONObject("site");
                JSONArray list = j.optJSONArray("news");
                runOnUiThread(() -> {
                    busy(false);
                    loaded = true;
                    fill(site);
                    setNews(list);
                });
            } catch (ServerApi.ApiException e) {
                fail(e);
            }
        });
    }

    private void fill(@Nullable JSONObject site) {
        if (site == null) return;
        b.title.setText(site.optString("title"));
        b.tagline.setText(site.optString("tagline"));
        b.hero.setText(site.optString("hero"));
        b.phone.setText(site.optString("phone"));
        b.hours.setText(site.optString("hours"));
        b.email.setText(site.optString("email"));
        b.topLeft.setText(site.optString("topLeft"));
        b.topRight.setText(site.optString("topRight"));
        b.delivery.setText(site.optString("delivery"));
        b.pay.setText(site.optString("pay"));
        b.about.setText(site.optString("about"));
    }

    // ------------------------------------------------------------ сохранение

    private void saveTexts() {
        if (!loaded) {
            // Пустые поля незагруженного экрана затёрли бы тексты на сайте.
            Ui.snack(b.getRoot(), getString(R.string.site_not_loaded));
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("title", Ui.text(b.title));
            body.put("tagline", Ui.text(b.tagline));
            body.put("hero", Ui.text(b.hero));
            body.put("phone", Ui.text(b.phone));
            body.put("hours", Ui.text(b.hours));
            body.put("email", Ui.text(b.email));
            body.put("topLeft", Ui.text(b.topLeft));
            body.put("topRight", Ui.text(b.topRight));
            body.put("delivery", Ui.text(b.delivery));
            body.put("pay", Ui.text(b.pay));
            body.put("about", Ui.text(b.about));
        } catch (JSONException e) {
            Ui.snack(b.getRoot(), getString(R.string.site_save_failed));
            return;
        }

        busy(true);
        ServerApi api = new ServerApi(this);
        Repository.get(this).io().execute(() -> {
            try {
                JSONObject j = api.saveSite(body);
                JSONObject site = j.optJSONObject("site");
                runOnUiThread(() -> {
                    busy(false);
                    fill(site); // сервер вернул то, что реально сохранилось
                    Ui.snack(b.getRoot(), getString(R.string.site_saved));
                });
            } catch (ServerApi.ApiException e) {
                fail(e);
            }
        });
    }

    // --------------------------------------------------------------- новости

    private void setNews(@Nullable JSONArray list) {
        news.clear();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item != null) news.add(item);
            }
        }
        showNews();
    }

    private void showNews() {
        b.newsList.removeAllViews();
        b.newsEmpty.setVisibility(news.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = getLayoutInflater();
        for (JSONObject item : news) {
            ItemNewsBinding row = ItemNewsBinding.inflate(inflater, b.newsList, false);
            row.text.setText(item.optString("text"));
            long created = item.optLong("createdAt");
            row.date.setText(created == 0 ? "" : Dates.formatDateTime(created));
            row.published.setChecked(item.optBoolean("published"));
            row.published.setOnCheckedChangeListener((v, checked) -> {
                if (checked == item.optBoolean("published")) return;
                sendNews(item.optLong("id"), item.optString("text"), checked);
            });
            row.toGroup.setOnClickListener(v -> sendToGroup(item));
            row.edit.setOnClickListener(v -> editNews(item));
            row.delete.setOnClickListener(v -> confirmDelete(item));
            b.newsList.addView(row.getRoot());
        }
    }

    /** Отправить новость в группу MAX — тем же текстом, что видно на сайте. */
    private void sendToGroup(JSONObject item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.news_to_group)
                .setMessage(item.optString("text"))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    busy(true);
                    ServerApi api = new ServerApi(this);
                    Repository.get(this).io().execute(() -> {
                        try {
                            api.botAnnounce(item.optLong("id"), item.optString("text"));
                            runOnUiThread(() -> {
                                busy(false);
                                Ui.snack(b.getRoot(), getString(R.string.news_sent));
                            });
                        } catch (ServerApi.ApiException e) {
                            fail(e);
                        }
                    });
                })
                .show();
    }

    /** Диалог с текстом новости: item = null — создаём новую. */
    private void editNews(@Nullable JSONObject item) {
        View view = getLayoutInflater().inflate(R.layout.dialog_news, null, false);
        TextInputEditText field = view.findViewById(R.id.text);
        TextInputLayout wrap = view.findViewById(R.id.textWrap);
        if (item != null) field.setText(item.optString("text"));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(item == null ? R.string.site_news_add : R.string.site_news_edit)
                .setView(view)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        // Кнопку перехватываем: с пустым текстом диалог закрываться не должен.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = Ui.text((EditText) field);
            if (text.isEmpty()) {
                wrap.setError(getString(R.string.site_news_empty_error));
                return;
            }
            dialog.dismiss();
            sendNews(item == null ? 0 : item.optLong("id"), text,
                    item == null || item.optBoolean("published"));
        });
    }

    private void confirmDelete(JSONObject item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.site_news_delete)
                .setMessage(item.optString("text"))
                .setPositiveButton(R.string.delete, (d, w) -> deleteNews(item.optLong("id")))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Создать или изменить новость и перечитать список — он и так короткий. */
    private void sendNews(long id, String text, boolean published) {
        JSONObject body = new JSONObject();
        try {
            body.put("id", id);
            body.put("text", text);
            body.put("published", published);
        } catch (JSONException e) {
            Ui.snack(b.getRoot(), getString(R.string.site_save_failed));
            return;
        }
        busy(true);
        ServerApi api = new ServerApi(this);
        Repository.get(this).io().execute(() -> {
            try {
                api.saveNews(body);
                reloadNews();
            } catch (ServerApi.ApiException e) {
                fail(e);
            }
        });
    }

    private void deleteNews(long id) {
        busy(true);
        ServerApi api = new ServerApi(this);
        Repository.get(this).io().execute(() -> {
            try {
                api.deleteNews(id);
                reloadNews();
            } catch (ServerApi.ApiException e) {
                fail(e);
            }
        });
    }

    /** Вызывается из фонового потока: тянет свежий список и показывает его. */
    private void reloadNews() {
        try {
            JSONArray list = new ServerApi(this).site().optJSONArray("news");
            runOnUiThread(() -> {
                busy(false);
                setNews(list);
            });
        } catch (ServerApi.ApiException e) {
            fail(e);
        }
    }

    // ------------------------------------------------------------ мелочи

    private void busy(boolean on) {
        b.progress.setVisibility(on ? View.VISIBLE : View.GONE);
        b.save.setEnabled(!on);
        b.addNews.setEnabled(!on);
    }

    private void fail(ServerApi.ApiException e) {
        runOnUiThread(() -> {
            busy(false);
            Ui.snack(b.getRoot(), e.getMessage());
        });
    }
}
