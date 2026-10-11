package com.embabel.publisher.it.app;

import com.embabel.publisher.annotation.RealmType;
import com.embabel.publisher.where.Op;

import java.math.BigDecimal;
import java.time.LocalDate;

@RealmType(name = "Invoice", entity = Invoice.class)
public record InvoiceView(
        @RealmType.Id String number,
        @RealmType.Path("customer.id")
        @RealmType.Reference(type = CustomerView.class, relationship = "BILLED", direction = RealmType.Direction.IN)
        @RealmType.Filterable({Op.EQ, Op.IN}) Long customerId,
        @RealmType.Filterable({Op.GT, Op.GTE, Op.LT, Op.LTE, Op.BETWEEN}) BigDecimal amount,
        @RealmType.Filterable @RealmType.Sortable LocalDate dueDate,
        @RealmType.Filterable({Op.EQ, Op.IN}) Status status) {

    static InvoiceView of(Invoice i) {
        return new InvoiceView(i.getNumber(), i.getCustomer().getId(), i.getAmount(), i.getDueDate(), i.getStatus());
    }
}
