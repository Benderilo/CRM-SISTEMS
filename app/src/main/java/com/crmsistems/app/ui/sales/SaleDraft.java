package com.crmsistems.app.ui.sales;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.Product;
import com.crmsistems.app.data.entity.Sale;
import com.crmsistems.app.ui.common.PickDialog;
import com.crmsistems.app.ui.common.Rows;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Собираемая продажа: позиции добавляются по одной — так же, как состав
 * заказа в его карточке. Пока не сохранили, живёт только на экране.
 * <p>
 * Используется и в карточке клиента, и на экране «Оформить заказ»:
 * складывать товары в продажу там надо одинаково.
 */
public class SaleDraft {

    /** Позиция продажи: товар и сколько его берут. */
    public static class Line {
        public final Product product;
        public double qty;

        Line(Product product, double qty) {
            this.product = product;
            this.qty = qty;
        }

        public double total() {
            return qty * product.price;
        }
    }

    private final AppCompatActivity activity;
    private final Runnable onChanged;
    private final List<Line> lines = new ArrayList<>();

    public SaleDraft(AppCompatActivity activity, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public double total() {
        double sum = 0;
        for (Line line : lines) sum += line.total();
        return sum;
    }

    public void clear() {
        lines.clear();
        onChanged.run();
    }

    /**
     * Выбор товара — тот же список, что при добавлении позиции в заказ.
     * Фильтра по serverId нет: продажа живёт в телефоне, пока товар не
     * выгрузится на сайт, и уходит заказом уже потом.
     */
    public void askProduct() {
        Repository repo = Repository.get(activity);
        repo.query(() -> repo.db().productDao().getActive(), products -> {
            if (products == null || products.isEmpty()) {
                Ui.snack(activity.findViewById(android.R.id.content),
                        activity.getString(R.string.sale_no_products));
                return;
            }
            PickDialog.show(activity, R.string.order_item_add,
                    PickDialog.products(activity, products),
                    which -> askQty(products.get(which)));
        });
    }

    /** Добавить товар в продажу, минуя выбор из списка. */
    public void add(Product product, double qty) {
        lines.add(new Line(product, qty));
        onChanged.run();
    }

    /** Сколько берут выбранного товара. */
    private void askQty(Product product) {
        qtyDialog(product.name,
                activity.getString(R.string.order_item_price, Money.format(product.price),
                        product.unit, Money.qty(product.stock)),
                "1", R.string.order_item_add_ok, false,
                qty -> {
                    lines.add(new Line(product, qty));
                    onChanged.run();
                    Ui.snack(activity.findViewById(android.R.id.content),
                            activity.getString(R.string.sale_item_added, product.name));
                }, null);
    }

    /** Состав продажи — так же, как состав заказа в его карточке. */
    public void render(LinearLayout box) {
        box.removeAllViews();
        if (lines.isEmpty()) return;

        for (Line line : lines) {
            View view = Rows.line(activity, line.product.name,
                    Money.qty(line.qty) + " " + line.product.unit + " × "
                            + Money.format(line.product.price),
                    Money.format(line.total()), false);
            view.setOnClickListener(v -> editLine(line));
            view.setBackgroundResource(Rows.selectableBackground(activity));
            box.addView(view);
        }
        box.addView(Rows.line(activity, activity.getString(R.string.sale_total), null,
                Money.format(total()), true));
    }

    /** Изменить количество позиции или убрать её из продажи. */
    private void editLine(Line line) {
        qtyDialog(line.product.name,
                activity.getString(R.string.order_item_current, Money.qty(line.qty),
                        line.product.unit, Money.format(line.product.price)),
                Money.qty(line.qty), R.string.save, true,
                qty -> {
                    line.qty = qty;
                    onChanged.run();
                },
                () -> new AlertDialog.Builder(activity)
                        .setTitle(R.string.order_item_remove)
                        .setMessage(activity.getString(R.string.sale_item_remove_confirm,
                                line.product.name))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete, (d, w) -> {
                            lines.remove(line);
                            onChanged.run();
                        })
                        .show());
    }

    private interface OnQty {
        void onQty(double qty);
    }

    /**
     * Диалог количества — общий для «добавить» и «изменить». Кнопки
     * перехватываем: с пустым количеством диалог закрываться не должен.
     */
    private void qtyDialog(String title, String current, String value, int okText,
                           boolean removable, OnQty onQty, Runnable onRemove) {
        View view = activity.getLayoutInflater().inflate(R.layout.dialog_order_item, null, false);
        TextView currentView = view.findViewById(R.id.current);
        TextView hint = view.findViewById(R.id.hint);
        com.google.android.material.textfield.TextInputEditText field = view.findViewById(R.id.qty);
        com.google.android.material.textfield.TextInputLayout wrap = view.findViewById(R.id.qtyWrap);

        currentView.setText(current);
        hint.setText(R.string.sale_item_hint);
        field.setText(value);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(view)
                .setPositiveButton(okText, null)
                .setNegativeButton(R.string.cancel, null);
        if (removable) builder.setNeutralButton(R.string.order_item_remove, null);

        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double qty = Money.parse(Ui.text(field));
            if (qty <= 0) {
                wrap.setError(activity.getString(R.string.order_item_bad_qty));
                return;
            }
            dialog.dismiss();
            onQty.onQty(qty);
        });
        if (removable) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                dialog.dismiss();
                onRemove.run();
            });
        }
    }

    /**
     * Позиции строками продажи. Клиент, дата и комментарий у них общие —
     * на сайт такая продажа уходит одним заказом.
     */
    public List<Sale> toSales(Long clientId, String clientName, long date, String note) {
        List<Sale> sales = new ArrayList<>();
        for (Line line : lines) {
            Sale sale = new Sale();
            sale.clientId = clientId;
            sale.clientName = clientName == null ? "" : clientName;
            sale.productId = line.product.id;
            sale.productName = line.product.title();
            sale.qty = line.qty;
            sale.unitPrice = line.product.price;
            sale.unitCost = line.product.cost;
            sale.date = date;
            sale.note = note == null ? "" : note;
            sales.add(sale);
        }
        return sales;
    }
}
