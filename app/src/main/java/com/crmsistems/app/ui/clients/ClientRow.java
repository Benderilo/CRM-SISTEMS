package com.crmsistems.app.ui.clients;

import com.crmsistems.app.data.entity.Client;

/** Клиент вместе с посчитанной суммой покупок — то, что видно в списке. */
public class ClientRow {

    public final Client client;
    public final double total;
    public final int salesCount;
    public final long lastSale;

    public ClientRow(Client client, double total, int salesCount, long lastSale) {
        this.client = client;
        this.total = total;
        this.salesCount = salesCount;
        this.lastSale = lastSale;
    }
}
