package com.embabel.publisher.it.app;

import com.embabel.publisher.annotation.RealmLookup;
import com.embabel.publisher.annotation.RealmQuery;
import com.embabel.publisher.where.Op;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Range;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository products;

    ProductService(ProductRepository products) {
        this.products = products;
    }

    /** The search the application's own screen would use. Every component left null is no filter. */
    public record ProductSearch(
            Category category,
            @RealmQuery.Criterion(property = "category") Collection<Category> categories,
            @RealmQuery.Criterion(property = "name", op = Op.PREFIX) String namePrefix,
            Range<BigDecimal> price,
            @RealmQuery.Criterion(property = "launched", op = Op.GTE) LocalDate launchedFrom) {
    }

    @RealmQuery(maxPageSize = 3)
    public Page<ProductView> search(ProductSearch search, Pageable pageable) {
        BigDecimal from = search.price() == null ? null : search.price().getLowerBound().getValue().orElse(null);
        BigDecimal to = search.price() == null ? null : search.price().getUpperBound().getValue().orElse(null);
        String prefix = search.namePrefix() == null ? null
                : search.namePrefix().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        Collection<Category> categories = search.categories() == null ? EnumSet.allOf(Category.class) : search.categories();
        return products.search(search.category(), search.categories() == null, categories, prefix, from, to, search.launchedFrom(), pageable)
                .map(ProductView::of);
    }

    public record ProductFilter(Category category) {
    }

    /* Carries only category eq; the publisher evaluates the rest of a pushed filter over what this returns. */
    @RealmLookup(by = "id", maxKeys = 20)
    public List<ProductView> byIds(Collection<Long> ids, ProductFilter filter) {
        return products.findByIds(ids, filter.category()).stream().map(ProductView::of).toList();
    }
}
