package com.crmsistems.app.ui.clients;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crmsistems.app.R;
import com.crmsistems.app.data.entity.Client;
import com.crmsistems.app.databinding.FragmentClientsBinding;
import com.crmsistems.app.net.SyncManager;
import com.crmsistems.app.ui.common.Chips;
import com.crmsistems.app.ui.common.SegmentColors;
import com.crmsistems.app.ui.common.SimpleTextWatcher;
import com.crmsistems.app.util.Money;
import com.crmsistems.app.util.Prefs;
import com.crmsistems.app.util.Ui;
import com.google.android.material.chip.Chip;

/**
 * Список клиентов с суммой покупок и FAB «Добавить клиента». Под поиском —
 * чипы-сегменты в цветах карточек (как статусы в «Продажах»), ниже — сортировка.
 */
public class ClientsFragment extends Fragment {

    private FragmentClientsBinding b;
    private ClientsViewModel vm;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentClientsBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        vm = new ViewModelProvider(this).get(ClientsViewModel.class);

        ClientAdapter adapter = new ClientAdapter(client ->
                startActivity(ClientDetailActivity.intent(requireContext(), client.id)));
        b.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.list.setAdapter(adapter);
        b.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 8) b.fab.shrink();
                else if (dy < -8) b.fab.extend();
            }
        });

        b.emptyView.emptyIcon.setImageResource(R.drawable.ic_clients);
        b.emptyView.emptyText.setText(R.string.clients_empty);

        b.searchInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onChanged(String text) {
                vm.setQuery(text);
            }
        });

        // Фильтр по сегменту — каждый чип в цвете своей карточки клиента:
        // постоянный зелёный, VIP золотой, проблемный красный и так далее.
        for (int i = 0; i < Client.SEGMENTS.length; i++) {
            paintSegmentChip(segmentChip(i), Client.SEGMENTS[i]);
        }

        b.segmentGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                b.chipSegAll.setChecked(true);
                return;
            }
            vm.setSegment(segmentOf(checkedIds.get(0)));
        });

        b.sortGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                b.chipName.setChecked(true);
                return;
            }
            int id = checkedIds.get(0);
            if (id == R.id.chipNew) vm.setMode(ClientsViewModel.SORT_NEW);
            else if (id == R.id.chipDebt) vm.setMode(ClientsViewModel.ONLY_WITH_SALES);
            else vm.setMode(ClientsViewModel.SORT_NAME);
        });

        b.fab.setOnClickListener(v ->
                startActivity(ClientEditorActivity.intent(requireContext(), 0)));

        // Потянули список вниз — синхронизация с сайтом: правки из админки,
        // блокировки и удаления подтянутся сами через LiveData.
        b.swipe.setOnRefreshListener(() -> {
            if (!Prefs.serverReady(requireContext())) {
                b.swipe.setRefreshing(false);
                Ui.snack(b.getRoot(), getString(R.string.server_not_configured));
                return;
            }
            new SyncManager(requireContext()).syncAll(result -> {
                if (b == null) return;
                b.swipe.setRefreshing(false);
                if (!result.ok()) Ui.snack(b.getRoot(), result.summary());
            });
        });

        vm.visible().observe(getViewLifecycleOwner(), rows -> {
            adapter.submitList(rows);
            boolean empty = rows == null || rows.isEmpty();
            b.emptyView.emptyRoot.setVisibility(empty ? View.VISIBLE : View.GONE);
            b.summary.setText(getString(R.string.nav_clients) + ": " + vm.totalClients()
                    + " · выручка " + Money.format(vm.totalRevenue()));
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }

    /** Чип сегмента по номеру в {@link Client#SEGMENTS}. */
    private Chip segmentChip(int index) {
        switch (index) {
            case 0: return b.chipSegNew;
            case 1: return b.chipSegRegular;
            case 2: return b.chipSegVip;
            case 3: return b.chipSegSleeping;
            default: return b.chipSegProblem;
        }
    }

    /** Сегмент выбранного чипа; {@code null} — фильтр выключен, показать всех. */
    private String segmentOf(int id) {
        for (int i = 0; i < Client.SEGMENTS.length; i++) {
            if (segmentChip(i).getId() == id) return Client.SEGMENTS[i];
        }
        return null;
    }

    /** Чип сегмента красится той же парой цветов, что и карточка клиента. */
    private void paintSegmentChip(Chip chip, String segment) {
        chip.setText(segment);
        Chips.paint(chip,
                SegmentColors.background(chip.getContext(), segment),
                SegmentColors.foreground(chip.getContext(), segment));
    }
}
