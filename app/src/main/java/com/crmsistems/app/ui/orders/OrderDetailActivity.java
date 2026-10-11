package com.crmsistems.app.ui.orders;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.Client;
import com.crmsistems.app.databinding.ActivityOrderDetailBinding;
import com.crmsistems.app.net.ServerApi;
import com.crmsistems.app.net.SyncManager;
import com.crmsistems.app.ui.clients.ClientEditorActivity;
import com.crmsistems.app.ui.common.PickDialog;
import com.crmsistems.app.ui.common.Rows;
import com.crmsistems.app.ui.common.StatusColors;
import com.crmsistems.app.util.Calls;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Phones;
import com.crmsistems.app.util.Ui;

/**
 * Карточка заказа с сайта: состав, данные покупателя и смена статуса.
 * Статус меняется прямо на сервере — при отмене он сам вернёт товар на склад.
 */
public class OrderDetailActivity extends AppCompatActivity {

    public static final String EXTRA_ORDER = "order";

    private ActivityOrderDetailBinding b;
    private ServerOrder order;

    /** Новая карточка из заявки: после сохранения привязываем заказ к ней. */
    private final ActivityResultLauncher<Intent> newClient =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                long id = result.getData().getLongExtra(ClientEditorActivity.EXTRA_ID, 0);
                if (id == 0) return;
                linkNewClient(id);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityOrderDetailBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.scroll);

        order = (ServerOrder) getIntent().getSerializableExtra(EXTRA_ORDER);
        if (order == null) {
            finish();
            return;
        }

        b.appbarInc.toolbar.setTitle(getString(R.string.order_number, order.number));
        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());

        b.changeStatus.setOnClickListener(v -> askStatus());
        b.deleteOrder.setOnClickListener(v -> askDelete());
        b.call.setOnClickListener(v -> call());
        b.addItem.setOnClickListener(v -> askProduct());
        b.pickClient.setOnClickListener(v -> askClient());

        fill();
    }

    private void fill() {
        b.status.setText(order.statusName);
        // Плашка статуса — в тех же цветах, что и карточка в списке.
        b.status.setBackgroundColor(StatusColors.background(this, order.status));
        b.status.setTextColor(StatusColors.foreground(this, order.status));
        b.date.setText(Dates.formatDateTime(order.createdAt));

        StringBuilder who = new StringBuilder();
        if (!TextUtils.isEmpty(order.clientName)) who.append(order.clientName).append('\n');
        // Телефон в этот блок не пишем — он крупно написан на кнопке звонка.
        if (!TextUtils.isEmpty(order.email)) who.append(order.email).append('\n');
        who.append(getString(R.string.order_delivery)).append(": ").append(order.delivery);
        if (!TextUtils.isEmpty(order.address)) who.append('\n').append(order.address);
        who.append('\n').append(getString(R.string.order_payment)).append(": ").append(order.payMethod);
        if (!TextUtils.isEmpty(order.comment)) {
            who.append('\n').append(getString(R.string.order_comment)).append(": ").append(order.comment);
        }
        b.customer.setText(who.toString());

        boolean hasPhone = !TextUtils.isEmpty(order.phone);
        b.call.setVisibility(hasPhone ? View.VISIBLE : View.GONE);
        if (hasPhone) {
            b.call.setText(getString(R.string.order_call_number, Phones.pretty(order.phone)));
        }
        // Заказы с одного номера сайт складывает в один — предупреждаем, что
        // в составе может оказаться больше, чем покупатель набрал за раз.
        b.mergedHint.setVisibility(order.fromApp() ? View.GONE : View.VISIBLE);
        // Привязать к клиенту предлагаем, пока в заявке нет телефона: с
        // номером покупатель и так найден по карточке. Без номера — это та
        // самая заявка из MAX, где бот не смог опознать человека и оставил
        // в комментарии подсказку, кто это может быть.
        b.pickClient.setVisibility(hasPhone ? View.GONE : View.VISIBLE);

        // Отменённый заказ не правим: товар по нему уже вернулся на склад.
        boolean editable = order.status != ServerOrder.STATUS_CANCELED;
        b.itemsHint.setVisibility(editable ? View.VISIBLE : View.GONE);
        b.addItem.setVisibility(editable ? View.VISIBLE : View.GONE);
        // Удалять предлагаем только отменённый: пока заказ в работе, для него
        // есть статусы, а отменённый уже никому не нужен.
        b.deleteOrder.setVisibility(editable ? View.GONE : View.VISIBLE);

        b.itemsBox.removeAllViews();
        for (ServerOrder.Item it : order.items) {
            View line = Rows.line(this, it.name,
                    Money.qty(it.qty) + " " + it.unit + " × " + Money.format(it.unitPrice),
                    Money.format(it.sum), false);
            if (editable && it.id > 0) {
                line.setOnClickListener(v -> askItemQty(it));
                line.setBackgroundResource(Rows.selectableBackground(this));
            }
            b.itemsBox.addView(line);
        }
        b.itemsBox.addView(Rows.line(this, getString(R.string.order_total), null,
                Money.format(order.total), true));
        if (order.profit != 0) {
            b.itemsBox.addView(Rows.line(this, getString(R.string.order_profit), null,
                    Money.format(order.profit), false));
        }
    }

    private void askStatus() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.order_change_status)
                .setSingleChoiceItems(ServerOrder.STATUS_NAMES, order.status, (d, which) -> {
                    d.dismiss();
                    saveStatus(which);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Удаление заказа — с подтверждением: вернуть его будет неоткуда. */
    private void askDelete() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.order_number, order.number))
                .setMessage(R.string.order_delete_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> delete())
                .show();
    }

    private void delete() {
        b.progress.setVisibility(View.VISIBLE);
        b.deleteOrder.setEnabled(false);

        ServerApi api = new ServerApi(this);
        Repository.get(this).io().execute(() -> {
            String error = null;
            try {
                api.deleteOrder(order.id);
                // Заказа больше нет на сайте — помечаем удалённым и в кэше
                // телефона: из списка он пропадёт, а в выгрузку попадёт.
                Repository.get(this).db().orderDao()
                        .markDeleted(order.id, System.currentTimeMillis());
            } catch (ServerApi.ApiException e) {
                // В очередь офлайн-правок удаление не кладём: слишком дорогая
                // ошибка, если оно уйдёт не тогда, когда его ждали.
                error = e.code == 0 ? getString(R.string.order_delete_offline) : e.getMessage();
            }
            final String err = error;
            runOnUiThread(() -> {
                b.progress.setVisibility(View.GONE);
                b.deleteOrder.setEnabled(true);
                if (err != null) {
                    Ui.snack(b.getRoot(), err);
                    return;
                }
                OrderNotifier.refreshFromCache(this);
                Toast.makeText(this, R.string.order_deleted, Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    private void saveStatus(int status) {
        if (status == order.status) return;

        b.progress.setVisibility(View.VISIBLE);
        b.changeStatus.setEnabled(false);

        ServerApi api = new ServerApi(this);
        Repository.get(this).io().execute(() -> {
            String error = null;
            boolean queued = false;
            try {
                api.setOrderStatus(order.id, status);
                // Сервер принял — фиксируем новый статус и в кэше телефона,
                // чтобы офлайн-просмотр показывал актуальное значение.
                Repository.get(this).db().orderDao()
                        .updateStatus(order.id, status, ServerOrder.statusName(status));
            } catch (ServerApi.ApiException e) {
                if (e.code == 0) {
                    // Нет связи — запоминаем правку и применяем её локально (оптимистично).
                    // Уйдёт на сайт при появлении интернета — сайт приоритетнее, поэтому
                    // отправляем такие правки до того, как заберём свежий список заказов.
                    SyncManager.enqueueOrderStatus(this, order.id, status);
                    queued = true;
                } else {
                    error = e.getMessage();
                }
            }
            if (error == null) {
                // Заказ ушёл в работу — напоминание в шторке пересобираем сразу,
                // не дожидаясь следующего обмена с сайтом.
                OrderNotifier.refreshFromCache(this);
            }
            final String err = error;
            final boolean done = queued;
            runOnUiThread(() -> {
                b.progress.setVisibility(View.GONE);
                b.changeStatus.setEnabled(true);
                if (err == null) {
                    order.status = status;
                    order.statusName = ServerOrder.statusName(status);
                    fill();
                    Ui.snack(b.getRoot(), getString(done
                            ? R.string.order_status_queued
                            : R.string.order_status_saved));
                } else {
                    Ui.snack(b.getRoot(), err);
                }
            });
        });
    }

    // --------------------------------------------------------- правка состава

    /**
     * Покупатель передумал: берёт меньше или отказывается от позиции.
     * Количество правится на сервере — он же пересчитает сумму заказа
     * и вернёт разницу на склад.
     */
    private void askItemQty(ServerOrder.Item item) {
        View view = getLayoutInflater().inflate(R.layout.dialog_order_item, null, false);
        TextView current = view.findViewById(R.id.current);
        com.google.android.material.textfield.TextInputEditText field = view.findViewById(R.id.qty);
        com.google.android.material.textfield.TextInputLayout wrap = view.findViewById(R.id.qtyWrap);

        current.setText(getString(R.string.order_item_current, Money.qty(item.qty),
                item.unit, Money.format(item.unitPrice)));
        field.setText(Money.qty(item.qty));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setView(view)
                .setPositiveButton(R.string.save, null)
                .setNeutralButton(R.string.order_item_remove, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();

        // Кнопки перехватываем: с пустым количеством диалог закрываться не должен,
        // а «Убрать» сначала спрашивает подтверждение.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double qty = Money.parse(Ui.text(field));
            if (qty <= 0) {
                wrap.setError(getString(R.string.order_item_bad_qty));
                return;
            }
            dialog.dismiss();
            saveItemQty(item, qty);
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            dialog.dismiss();
            confirmRemoveItem(item);
        });
    }

    /**
     * Связать заявку с карточкой клиента.
     *
     * <p>Телефон и адрес подтянутся из карточки. Заодно сервер запомнит, чей
     * это MAX, и в следующий раз бот опознает покупателя сам — по его ID,
     * без имён и подсказок.
     */
    private void askClient() {
        Repository.get(this).io().execute(() -> {
            java.util.List<Client> ready = new java.util.ArrayList<>();
            for (Client c : Repository.get(this).db().clientDao().getActive()) {
                if (c.serverId > 0) ready.add(c);
            }
            runOnUiThread(() -> {
                java.util.List<PickDialog.Option> options = new java.util.ArrayList<>();
                for (Client c : ready) {
                    options.add(new PickDialog.Option(c.title(), Phones.pretty(c.phone), ""));
                }
                // Даже если список пуст, диалог всё равно показываем: остаётся
                // дорога «Создать клиента» — завести карточку прямо из заявки.
                PickDialog.show(this, R.string.order_pick_client, options, which -> {
                    Client c = ready.get(which);
                    sendOrderChange(api -> api.setOrderClient(order.id, c.serverId),
                            getString(R.string.order_client_linked, c.title()));
                }, R.string.order_client_new, this::createClient);
            });
        });
    }

    /**
     * Никто не подошёл — заводим нового клиента, подставив в карточку имя и
     * адрес из заявки. Телефон пользователь допишет сам: без него сайт
     * карточку не примет, а значит и привязать заявку будет нельзя.
     */
    private void createClient() {
        newClient.launch(ClientEditorActivity.prefillIntent(this,
                order.clientName, order.email, order.address));
    }

    /** Карточка сохранена — привязываем заявку, если сайт её уже принял. */
    private void linkNewClient(long id) {
        Repository.get(this).io().execute(() -> {
            Client c = Repository.get(this).db().clientDao().getById(id);
            runOnUiThread(() -> {
                if (c == null) return;
                if (c.serverId <= 0) {
                    // Редактор уже объяснил тостом, почему карточка не доехала
                    // до сайта (нет телефона или связи) — здесь лишь про заявку.
                    Ui.snack(b.getRoot(), getString(R.string.order_client_not_linked));
                    return;
                }
                sendOrderChange(api -> api.setOrderClient(order.id, c.serverId),
                        getString(R.string.order_client_linked, c.title()));
            });
        });
    }

    /**
     * Дозаказ: выбираем товар из своего списка. Берём только те, что уже есть
     * на сайте (serverId), — заказ живёт там, и добавить в него можно лишь
     * знакомый сайту товар.
     */
    private void askProduct() {
        Repository.get(this).io().execute(() -> {
            java.util.List<com.crmsistems.app.data.entity.Product> all =
                    Repository.get(this).db().productDao().getActive();
            java.util.List<com.crmsistems.app.data.entity.Product> ready =
                    new java.util.ArrayList<>();
            for (com.crmsistems.app.data.entity.Product p : all) {
                if (p.serverId > 0) ready.add(p);
            }
            runOnUiThread(() -> {
                if (ready.isEmpty()) {
                    Ui.snack(b.getRoot(), getString(R.string.order_item_no_products));
                    return;
                }
                PickDialog.show(this, R.string.order_item_add,
                        PickDialog.products(this, ready), which -> askAddQty(ready.get(which)));
            });
        });
    }

    /** Сколько добавить выбранного товара. */
    private void askAddQty(com.crmsistems.app.data.entity.Product product) {
        View view = getLayoutInflater().inflate(R.layout.dialog_order_item, null, false);
        TextView current = view.findViewById(R.id.current);
        com.google.android.material.textfield.TextInputEditText field = view.findViewById(R.id.qty);
        com.google.android.material.textfield.TextInputLayout wrap = view.findViewById(R.id.qtyWrap);

        current.setText(getString(R.string.order_item_price, Money.format(product.price),
                product.unit, Money.qty(product.stock)));
        field.setText("1");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(product.name)
                .setView(view)
                .setPositiveButton(R.string.order_item_add_ok, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double qty = Money.parse(Ui.text(field));
            if (qty <= 0) {
                wrap.setError(getString(R.string.order_item_bad_qty));
                return;
            }
            dialog.dismiss();
            sendOrderChange(api -> api.addOrderItem(order.id, product.serverId, qty),
                    getString(R.string.order_item_added, product.name));
        });
    }

    private void confirmRemoveItem(ServerOrder.Item item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.order_item_remove)
                .setMessage(getString(R.string.order_item_remove_confirm, item.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> saveItemQty(item, 0))
                .show();
    }

    private void saveItemQty(ServerOrder.Item item, double qty) {
        sendOrderChange(api -> api.setOrderItemQty(order.id, item.id, qty),
                getString(R.string.order_item_saved));
    }

    /** Запрос к сайту, меняющий заказ: в ответе всегда приходит заказ целиком. */
    private interface OrderCall {
        org.json.JSONObject run(ServerApi api) throws ServerApi.ApiException;
    }

    private void sendOrderChange(OrderCall call, String successMessage) {
        b.progress.setVisibility(View.VISIBLE);
        b.changeStatus.setEnabled(false);
        b.addItem.setEnabled(false);

        ServerApi api = new ServerApi(this);
        Repository.get(this).io().execute(() -> {
            String error = null;
            ServerOrder updated = null;
            try {
                org.json.JSONObject j = call.run(api);
                updated = ServerOrder.from(j.getJSONObject("order"));
                // Обновляем и офлайн-копию, чтобы без сети показывался новый состав.
                Repository.get(this).db().orderDao().insert(updated.toEntity());
            } catch (ServerApi.ApiException e) {
                // Правка состава живёт только на сервере: в очередь её не ставим —
                // иначе склад и сумма разошлись бы с сайтом.
                error = e.code == 0 ? getString(R.string.order_item_locked) : e.getMessage();
            } catch (org.json.JSONException e) {
                error = getString(R.string.operation_failed);
            }

            final String err = error;
            final ServerOrder result = updated;
            runOnUiThread(() -> {
                b.progress.setVisibility(View.GONE);
                b.changeStatus.setEnabled(true);
                b.addItem.setEnabled(true);
                if (err != null) {
                    Ui.snack(b.getRoot(), err);
                    return;
                }
                order = result;
                fill();
                Ui.snack(b.getRoot(), successMessage);
            });
        });
    }

    private void call() {
        Calls.dial(this, order.phone);
    }
}
