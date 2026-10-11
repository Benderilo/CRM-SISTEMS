package com.crmsistems.app.ui.clients;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.crmsistems.app.R;
import com.crmsistems.app.data.Repository;
import com.crmsistems.app.data.entity.Client;
import com.crmsistems.app.databinding.ActivityClientEditorBinding;
import com.crmsistems.app.net.SyncManager;
import com.crmsistems.app.util.Dates;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Ui;

import com.google.android.material.datepicker.MaterialDatePicker;

/** Полная карточка клиента: создание и редактирование. */
public class ClientEditorActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "client_id";
    private static final String EXTRA_NAME = "prefill_name";
    private static final String EXTRA_EMAIL = "prefill_email";
    private static final String EXTRA_ADDRESS = "prefill_address";

    private ActivityClientEditorBinding b;
    private Repository repo;
    private Client client = new Client();

    private final ActivityResultLauncher<String[]> pickPhoto =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                }
                client.photoUri = uri.toString();
                showPhoto();
            });

    public static Intent intent(Context context, long id) {
        return new Intent(context, ClientEditorActivity.class).putExtra(EXTRA_ID, id);
    }

    /**
     * Новая карточка, заранее заполненная данными из заявки: заводим клиента
     * прямо из заказа, не переписывая имя и адрес руками.
     */
    public static Intent prefillIntent(Context context,
                                       @Nullable String name, @Nullable String email,
                                       @Nullable String address) {
        return new Intent(context, ClientEditorActivity.class)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_EMAIL, email)
                .putExtra(EXTRA_ADDRESS, address);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityClientEditorBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        Ui.applyInsets(b.root, b.appbarInc.getRoot(), b.bottomBar);

        repo = Repository.get(this);
        long id = getIntent().getLongExtra(EXTRA_ID, 0);

        b.appbarInc.toolbar.setNavigationOnClickListener(v -> finish());
        b.appbarInc.toolbar.setTitle(id == 0 ? R.string.add_client : R.string.client_card);
        if (id != 0) {
            b.appbarInc.toolbar.inflateMenu(R.menu.menu_editor);
            b.appbarInc.toolbar.setOnMenuItemClickListener(this::onMenu);
        }

        b.segment.setSimpleItems(Client.SEGMENTS);
        b.source.setSimpleItems(Client.SOURCES);

        b.pickPhoto.setOnClickListener(v -> pickPhoto.launch(new String[]{"image/*"}));
        b.clearPhoto.setOnClickListener(v -> {
            client.photoUri = "";
            showPhoto();
        });
        b.birthday.setOnClickListener(v -> pickBirthday());
        b.save.setOnClickListener(v -> save());

        if (id == 0) {
            Intent prefill = getIntent();
            client.name = orEmpty(prefill.getStringExtra(EXTRA_NAME));
            client.email = orEmpty(prefill.getStringExtra(EXTRA_EMAIL));
            client.address = orEmpty(prefill.getStringExtra(EXTRA_ADDRESS));
            fill();
        } else {
            repo.query(() -> repo.db().clientDao().getById(id), loaded -> {
                if (loaded == null) {
                    finish();
                    return;
                }
                client = loaded;
                fill();
            });
        }
    }

    private boolean onMenu(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_delete) {
            new AlertDialog.Builder(this)
                    .setTitle(client.title())
                    .setMessage(R.string.confirm_delete)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (d, w) ->
                            repo.deleteClient(client, this::finish))
                    .show();
            return true;
        }
        return false;
    }

    private void pickBirthday() {
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.client_birthday)
                .setSelection(client.birthday > 0 ? client.birthday : MaterialDatePicker.todayInUtcMilliseconds())
                .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            client.birthday = selection == null ? 0 : selection;
            b.birthday.setText(Dates.formatDate(client.birthday));
        });
        picker.show(getSupportFragmentManager(), "birthday");
    }

    private void fill() {
        b.name.setText(client.name);
        b.phone.setText(client.phone);
        b.email.setText(client.email);
        b.company.setText(client.company);
        b.address.setText(client.address);
        b.segment.setText(client.segment.isEmpty() ? Client.SEGMENTS[0] : client.segment, false);
        b.source.setText(client.source, false);
        b.tags.setText(client.tags);
        b.note.setText(client.note);
        b.archived.setChecked(client.archived);
        b.blocked.setChecked(client.blocked);
        if (client.birthday > 0) b.birthday.setText(Dates.formatDate(client.birthday));
        showPhoto();
    }

    private void showPhoto() {
        if (client.photoUri == null || client.photoUri.isEmpty()) {
            int pad = Ui.dp(this, 24);
            b.photo.setPadding(pad, pad, pad, pad);
            b.photo.setImageResource(R.drawable.ic_clients);
        } else {
            b.photo.setPadding(0, 0, 0, 0);
            b.photo.setImageTintList(null);
            try {
                b.photo.setImageURI(Uri.parse(client.photoUri));
            } catch (Exception e) {
                b.photo.setImageResource(R.drawable.ic_clients);
            }
        }
    }

    private void save() {
        String name = Ui.text(b.name);
        if (name.isEmpty()) {
            b.name.setError(getString(R.string.required_field));
            b.name.requestFocus();
            return;
        }
        client.name = name;
        client.phone = Ui.text(b.phone);
        client.email = Ui.text(b.email);
        client.company = Ui.text(b.company);
        client.address = Ui.text(b.address);
        client.segment = Ui.text(b.segment).isEmpty() ? Client.SEGMENTS[0] : Ui.text(b.segment);
        client.source = Ui.text(b.source);
        client.tags = Ui.text(b.tags);
        client.note = Ui.text(b.note);
        client.archived = b.archived.isChecked();
        client.blocked = b.blocked.isChecked();

        repo.saveClient(client, id -> {
            // Отвечаем заказу, какая карточка получилась, — он привяжет заявку к ней.
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_ID, id));
            pushToSite();
        });
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    /**
     * Отправить карточку на сайт: правки, архив и блокировка должны дойти
     * сразу, а не ждать полной синхронизации. Без связи правка встанет
     * в очередь и уйдёт при появлении интернета.
     */
    private void pushToSite() {
        if (!Prefs.serverReady(this)) {
            finish();
            return;
        }
        new SyncManager(this).pushClient(client, result -> {
            if (result != null && result.error != null) {
                Toast.makeText(this, result.error, Toast.LENGTH_LONG).show();
            }
            finish();
        });
    }
}
