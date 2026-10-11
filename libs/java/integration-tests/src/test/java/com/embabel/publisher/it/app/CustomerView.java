package com.embabel.publisher.it.app;

import com.embabel.publisher.annotation.RealmType;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.math.BigDecimal;
import java.time.LocalDate;

@RealmType(name = "Customer", entity = Customer.class, cache = @RealmType.Cache(value = RealmType.Scope.SHARED, ttlSeconds = 60))
@JsonClassDescription("A company that buys from us.")
public record CustomerView(
        @RealmType.Id Long id,
        @RealmType.Filterable @RealmType.Sortable String name,
        @RealmType.Filterable @RealmType.Sortable String city,
        @RealmType.Filterable Tier tier,
        @RealmType.Filterable @RealmType.Sortable @JsonPropertyDescription("Outstanding, in the account currency.") BigDecimal balance,
        @RealmType.Filterable LocalDate since,
        Long externalRef,
        @RealmType.Path("region.name") @RealmType.Filterable String region) {

    static CustomerView of(Customer c) {
        return new CustomerView(c.getId(), c.getName(), c.getCity(), c.getTier(), c.getBalance(), c.getSince(), c.getExternalRef(),
                c.getRegion() == null ? null : c.getRegion().getName());
    }
}
