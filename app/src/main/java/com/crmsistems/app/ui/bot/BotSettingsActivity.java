package com.crmsistems.app.ui.bot;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.databinding.ActivityBotSettingsBinding;
import com.crmsistems.app.net.ServerApi;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Hints;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Ui;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Бот MAX: токен, включение, перехват заявок и группа для новостей.
 * <p>
 * Токен живёт только на сервере — сюда приходит лишь маска вида «••••3f9a»,
 * поэтому подсмотреть его через приложение нельзя, а заменить можно в любой
 * момент: вписал новый, сохранил — сервер сам перезапустил бота. Так одного
 * и того же бота переносят на другой магазин.
 */
public class BotSettingsActivity extends AppCompatActivity {

    private ActivityBotSettingsBinding b;
    private boolean loaded;
    private long chatId;
    private String groupTitle = "";
    /** Час ночного перезапуска, 0–23: сервер поднимает опрос заново раз в сутки. */
    private int restartHour = 4;
    /** Есть ли на сервере ключ к модели: без него включать нечего. */
    private boolean aiReady;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityBotSettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.scroll);
        Hints.field(b.tokenLayout, R.string.help_bot_token);
        Hints.field(b.callWordsLayout, R.string.help_bot_call_words);
        Hints.field(b.ignorePhonesLayout, R.string.help_bot_ignore_phones);
        Hints.field(b.orderWordsLayout, R.string.help_bot_order_words);
        Hints.field(b.aiKeyLayout, R.string.help_bot_ai_key);
        Hints.field(b.aiModelLayout, R.string.help_bot_ai_model);
        Hints.field(b.aiUrlLayout, R.string.help_bot_ai_url);

        b.appbarInc.toolbar.setTitle(R.string.bot_title);
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());

        b.check.setOnClickListener(v -> check());
        b.chooseGroup.setOnClickListener(v -> chooseGroup());
        b.sendMenu.setOnClickListener(v -> sendMenuToGroup());
        b.restartTime.setOnClickListener(v -> chooseRestartHour());
        b.restartNow.setOnClickListener(v -> restartNow());
        b.save.setOnClickListener(v -> save());

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
                JSONObject j = api.bot().getJSONObject("bot");
                runOnUiThread(() -> {
                    busy(false);
                    loaded = true;
                    fill(j);
                });
            } catch (ServerApi.ApiException | JSONException e) {
                fail(e.getMessage());
            }
        });
    }

    private void fill(JSONObject bot) {
        b.status.setText(bot.optString("status", getString(R.string.bot_status_unknown)));
        b.enabled.setChecked(bot.optBoolean("enabled"));
        chatId = bot.optLong("chatId");
        b.callWords.setText(bot.optString("callWords"));
        // Номера-исключения сервер отдаёт уже приведёнными к единому виду,
        // по одному в строке — так их удобно править и удалять.
        b.ignorePhones.setText(bot.optString("ignorePhones"));
        b.orderWords.setText(bot.optString("orderWords"));
        showAI(bot);
        b.tokenHint.setText(bot.optBoolean("hasToken")
                ? getString(R.string.bot_token_saved, bot.optString("tokenMask"))
                : getString(R.string.bot_token_hint));
        b.restartDaily.setChecked(bot.optBoolean("restartDaily", true));
        restartHour = bot.optInt("restartHour", 4);
        showGroup();
        showRestart(bot);
    }

    /**
     * Переключатель разбора сообщений моделью.
     *
     * <p>Ключ к модели вписывается здесь же и уходит на сервер; обратно
     * сервер его не отдаёт — только отметку «задан». Пока ключа нет,
     * переключатель неактивен: включать нечего.
     */
    private void showAI(JSONObject bot) {
        aiReady = bot.optBoolean("aiReady");
        b.aiKey.setText("");
        b.aiKeyLayout.setHelperText(bot.optBoolean("aiKeySet")
                ? getString(R.string.bot_ai_key_set) : null);
        b.aiModel.setText(bot.optString("aiModel"));
        b.aiUrl.setText(bot.optString("aiUrl"));
        b.aiEnabled.setEnabled(aiReady);
        b.aiEnabled.setChecked(aiReady && bot.optBoolean("aiEnabled", true));
        b.aiHint.setText(aiReady
                ? getString(R.string.bot_ai_hint, bot.optString("aiModel"))
                : getString(R.string.bot_ai_missing));

        // Без модели решать, что спам, нечем — переключатель неактивен.
        // По умолчанию выключено: удалённое сообщение не вернуть.
        b.antiSpam.setEnabled(aiReady);
        b.antiSpam.setChecked(aiReady && bot.optBoolean("antiSpam"));
    }

    /**
     * Строка про перезапуск: с какого времени бот на связи, когда поднимался
     * заново и когда поднимется в следующий раз.
     */
    private void showRestart(JSONObject bot) {
        b.restartTime.setText(getString(R.string.bot_restart_time, hourLabel(restartHour)));

        StringBuilder text = new StringBuilder();
        long started = bot.optLong("startedAt");
        text.append(started > 0
                ? getString(R.string.bot_restart_started, Dates.formatDateTimeMsk(started))
                : getString(R.string.bot_restart_idle));

        long last = bot.optLong("lastRestart");
        if (last > 0) {
            text.append('\n').append(getString(R.string.bot_restart_last,
                    Dates.formatDateTimeMsk(last), bot.optString("restartReason")));
        }
        long next = bot.optLong("nextRestart");
        text.append('\n').append(next > 0
                ? getString(R.string.bot_restart_next, Dates.formatDateTimeMsk(next))
                : getString(R.string.bot_restart_off));
        b.restartInfo.setText(text.toString());
    }

    private String hourLabel(int hour) {
        return String.format(Dates.RU, "%02d:00", hour);
    }

    /** Во сколько перезапускать: ночью, когда заказов нет. */
    private void chooseRestartHour() {
        String[] hours = new String[24];
        for (int i = 0; i < 24; i++) {
            hours[i] = hourLabel(i);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.bot_restart_time_title)
                .setSingleChoiceItems(hours, restartHour, (d, which) -> {
                    restartHour = which;
                    b.restartTime.setText(getString(R.string.bot_restart_time, hourLabel(which)));
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Поднять опрос заново прямо сейчас — то же, что делает сервер ночью. */
    private void restartNow() {
        busy(true);
        ServerApi api = new ServerApi(this);
        Repository.get(this).io().execute(() -> {
            try {
                JSONObject j = api.botRestart().getJSONObject("bot");
                runOnUiThread(() -> {
                    busy(false);
                    fill(j);
                    Ui.snack(b.getRoot(), getString(R.string.bot_restart_done));
                });
            } catch (ServerApi.ApiException | JSONException e) {
                fail(e.getMessage());
            }
        });
    }

    private void showGroup() {
        if (chatId == 0) {
            b.group.setText(R.string.bot_group_none);
        } else if (groupTitle.isEmpty()) {
            b.group.setText(getString(R.string.bot_group_id, chatId));
        } else {
            b.group.setText(getString(R.string.bot_group_set, groupTitle));
        }
    }

    // ------------------------------------------------------------ действия

    /** Проверка токена: сервер спрашивает у MAX, чей он. */
    private void check() {
        busy(true);
        String token = Ui.text(b.token);
        ServerApi api = new ServerApi(this);
        Repository.get(this).io().execute(() -> {
            try {
                JSONObject j = api.botCheck(token);
                String title = j.optString("title", j.optString("name"));
                runOnUiThread(() -> {
                    busy(false);
                    b.status.setText(getString(R.string.bot_check_ok, title));
                    Ui.snack(b.getRoot(), getString(R.string.bot_check_ok, title));
                });
            } catch (ServerApi.ApiException e) {
                fail(e.getMessage());
            }
        });
    }

    /** Список чатов бота — выбираем, куда слать новости. */
    private void chooseGroup() {
        busy(true);
        ServerApi api = new ServerApi(this);
        Repository.get(this).io().execute(() -> {
            try {
                JSONArray chats = api.botChats();
                runOnUiThread(() -> {
                    busy(false);
                    if (chats.length() == 0) {
                        Ui.snack(b.getRoot(), getString(R.string.bot_group_empty));
                        return;
                    }
                    String[] titles = new String[chats.length()];
                    long[] ids = new long[chats.length()];
                    for (int i = 0; i < chats.length(); i++) {
                        JSONObject c = chats.optJSONObject(i);
                        titles[i] = c == null ? "?" : c.optString("title");
                        ids[i] = c == null ? 0 : c.optLong("chatId");
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.bot_group_choose)
                            .setItems(titles, (d, which) -> {
                                chatId = ids[which];
                                groupTitle = titles[which];
                                showGroup();
                            })
                            .setNeutralButton(R.string.bot_group_clear, (d, w) -> {
                                chatId = 0;
                                groupTitle = "";
                                showGroup();
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                });
            } catch (ServerApi.ApiException e) {
                fail(e.getMessage());
            }
        });
    }

    /** Карточка с кнопками в группу — её там закрепляют. */
    private void sendMenuToGroup() {
        busy(true);
        ServerApi api = new ServerApi(this);
        Repository.get(this).io().execute(() -> {
            try {
                api.botMenu();
                runOnUiThread(() -> {
                    busy(false);
                    Ui.snack(b.getRoot(), getString(R.string.bot_menu_sent));
                });
            } catch (ServerApi.ApiException e) {
                fail(e.getMessage());
            }
        });
    }

    private void save() {
        if (!loaded) {
            Ui.snack(b.getRoot(), getString(R.string.bot_not_loaded));
            return;
        }
        JSONObject body = new JSONObject();
        try {
            // Пустое поле означает «оставить прежний токен» — сервер его не отдаёт.
            body.put("token", Ui.text(b.token));
            body.put("enabled", b.enabled.isChecked());
            body.put("chatId", chatId);
            body.put("callWords", Ui.text(b.callWords));
            body.put("ignorePhones", Ui.text(b.ignorePhones));
            body.put("orderWords", Ui.text(b.orderWords));
            // Пока ключа модели на сервере нет, переключатель неактивен —
            // и поля не шлём: иначе сохранили бы «выключено», и разбор
            // остался бы выключенным даже после того, как ключ пропишут.
            String aiKey = Ui.text(b.aiKey);
            if (!aiKey.isEmpty()) body.put("aiKey", aiKey);
            body.put("aiModel", Ui.text(b.aiModel));
            body.put("aiUrl", Ui.text(b.aiUrl));
            if (aiReady) {
                body.put("aiEnabled", b.aiEnabled.isChecked());
                body.put("antiSpam", b.antiSpam.isChecked());
            } else if (!aiKey.isEmpty()) {
                // Ключ только что вписали — значит, разбором хотят пользоваться.
                body.put("aiEnabled", true);
            }
            body.put("restartDaily", b.restartDaily.isChecked());
            body.put("restartHour", restartHour);
            // Адрес витрины для кнопки «Открыть магазин» берём тот же, что у приложения.
            body.put("shopUrl", Prefs.serverUrl(this));
        } catch (JSONException e) {
            Ui.snack(b.getRoot(), getString(R.string.operation_failed));
            return;
        }

        busy(true);
        ServerApi api = new ServerApi(this);
        Repository.get(this).io().execute(() -> {
            try {
                JSONObject j = api.saveBot(body).getJSONObject("bot");
                runOnUiThread(() -> {
                    busy(false);
                    b.token.setText("");  // введённый токен больше не нужен на экране
                    fill(j);
                    Ui.snack(b.getRoot(), getString(R.string.bot_saved));
                });
            } catch (ServerApi.ApiException | JSONException e) {
                fail(e.getMessage());
            }
        });
    }

    // -------------------------------------------------------------- мелочи

    private void busy(boolean on) {
        b.progress.setVisibility(on ? View.VISIBLE : View.GONE);
        b.save.setEnabled(!on);
        b.check.setEnabled(!on);
        b.chooseGroup.setEnabled(!on);
        b.sendMenu.setEnabled(!on);
        b.restartNow.setEnabled(!on);
        b.restartTime.setEnabled(!on);
    }

    private void fail(String message) {
        runOnUiThread(() -> {
            busy(false);
            b.status.setText(message);
            Ui.snack(b.getRoot(), message);
        });
    }
}
