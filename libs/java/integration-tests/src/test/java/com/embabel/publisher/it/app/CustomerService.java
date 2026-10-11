package com.embabel.publisher.it.app;

import com.embabel.publisher.annotation.RealmLookup;
import com.embabel.publisher.annotation.RealmQuery;
import com.embabel.publisher.annotation.RealmVerb;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customers;

    CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    @RealmLookup(by = "id", maxKeys = 50)
    public List<CustomerView> byIds(Collection<Long> ids, Specification<Customer> where) {
        Specification<Customer> idIn = (root, query, cb) -> root.get("id").in(ids);
        return customers.findAll(idIn.and(where)).stream().map(CustomerView::of).toList();
    }

    @RealmQuery(maxPageSize = 5)
    public Page<CustomerView> search(Specification<Customer> where, Pageable pageable) {
        return customers.findAll(where, pageable).map(CustomerView::of);
    }

    public record Rename(@NotNull Long id, @NotBlank String name) {
    }

    public record Renamed(Long id, String name) {
    }

    @RealmVerb(description = "Rename a customer.", subject = CustomerView.class, effect = RealmVerb.Effect.WRITE, idempotent = true)
    @Transactional
    public Renamed rename(Rename request) {
        Customer customer = customers.findById(request.id())
                .orElseThrow(() -> new IllegalArgumentException("No customer " + request.id()));
        customer.setName(request.name());
        return new Renamed(customer.getId(), customer.getName());
    }

    public record WriteOff(@NotNull Long customerId, @NotNull BigDecimal amount) {
    }

    public record WrittenOff(BigDecimal balance) {
    }

    @RealmVerb(description = "Write off part of a customer's balance.", subject = CustomerView.class, effect = RealmVerb.Effect.WRITE,
            approval = @RealmVerb.Approval(required = RealmVerb.Required.ALWAYS, reason = "Write-offs need a second person."))
    @Transactional
    public WrittenOff writeOff(WriteOff request) {
        Customer customer = customers.findById(request.customerId())
                .orElseThrow(() -> new IllegalArgumentException("No customer " + request.customerId()));
        BigDecimal balance = customer.getBalance() == null ? BigDecimal.ZERO : customer.getBalance();
        customer.setBalance(balance.subtract(request.amount()));
        return new WrittenOff(customer.getBalance());
    }
}
