package com.embabel.publisher.it.app;

import com.embabel.publisher.annotation.RealmType;

import java.math.BigDecimal;
import java.time.LocalDate;

/* No @RealmType.Filterable: the service's criteria records declare what can be pushed down. */
@RealmType(name = "Product", entity = Product.class)
public record ProductView(@RealmType.Id Long id, @RealmType.Sortable String sku, String name, Category category, BigDecimal price,
                          LocalDate launched) {

    static ProductView of(Product p) {
        return new ProductView(p.getId(), p.getSku(), p.getName(), p.getCategory(), p.getPrice(), p.getLaunched());
    }
}
