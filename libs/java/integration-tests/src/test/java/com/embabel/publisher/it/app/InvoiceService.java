package com.embabel.publisher.it.app;

import com.embabel.publisher.annotation.RealmLookup;
import com.embabel.publisher.annotation.RealmQuery;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class InvoiceService {

    private final InvoiceRepository invoices;

    InvoiceService(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    /* Takes no Specification: the publisher filters what it returns with the reference evaluator. */
    @RealmLookup(by = "number", maxKeys = 3)
    public List<InvoiceView> byNumbers(Collection<String> numbers) {
        return invoices.findByNumberIn(numbers).stream().map(InvoiceView::of).toList();
    }

    @RealmLookup(by = "customerId", maxKeys = 100)
    public List<InvoiceView> byCustomers(Collection<Long> customerIds, Specification<Invoice> where, Sort sort) {
        Specification<Invoice> ofCustomers = (root, query, cb) -> root.get("customer").get("id").in(customerIds);
        return invoices.findAll(ofCustomers.and(where), sort).stream().map(InvoiceView::of).toList();
    }

    /* One bounded page and no cursor: a walk past it is reported as truncated. */
    @RealmQuery(maxPageSize = 3)
    public List<InvoiceView> recent(Specification<Invoice> where, Sort sort, Limit limit) {
        return invoices.findBy(where, q -> q.sortBy(sort).limit(limit.max()).all()).stream().map(InvoiceView::of).toList();
    }
}
