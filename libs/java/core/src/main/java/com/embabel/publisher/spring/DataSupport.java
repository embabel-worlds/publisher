package com.embabel.publisher.spring;

import com.embabel.publisher.realm.QueryBinding;
import com.embabel.publisher.spring.jpa.SpecificationTranslator;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Spring Data Commons values: {@code Sort}, {@code Pageable}, {@code Limit}, and pages back. Kept
 * apart so an application without Spring Data never loads it.
 */
final class DataSupport {

    private DataSupport() {
    }

    /** A Spring Data {@code Sort} for the order: nulls as the greatest value, last ascending and first descending (§4.1). */
    static Sort sort(List<SpecificationTranslator.OrderKey> order) {
        List<Sort.Order> orders = new ArrayList<>();
        for (SpecificationTranslator.OrderKey key : order) {
            Sort.Order plain = key.descending() ? Sort.Order.desc(key.entityPath()) : Sort.Order.asc(key.entityPath());
            /* A key that cannot be null needs no null placement, which Spring Data JPA 3 cannot express. */
            orders.add(!key.nullable() ? plain : key.descending() ? plain.nullsFirst() : plain.nullsLast());
        }
        return orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
    }

    static Sort unsorted() {
        return Sort.unsorted();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object closedRange(Object low, Object high) {
        return org.springframework.data.domain.Range.closed((Comparable) low, (Comparable) high);
    }

    static Object pageable(int page, int size, Sort sort) {
        return PageRequest.of(page, size, sort);
    }

    static Object limit(int max) {
        return Limit.of(max);
    }

    static QueryBinding.Page page(Object result, QueryBinding.Paging paging, int pageSize) {
        return switch (paging) {
            case PAGE -> {
                Page<?> page = (Page<?>) result;
                yield new QueryBinding.Page(page.getContent(), page.hasNext(), page.getTotalElements(), true);
            }
            case SLICE -> {
                Slice<?> slice = (Slice<?>) result;
                yield new QueryBinding.Page(slice.getContent(), slice.hasNext(), null, true);
            }
            case LIMIT -> {
                List<?> items = new ArrayList<>((Collection<?>) result);
                boolean more = items.size() > pageSize;
                yield new QueryBinding.Page(more ? items.subList(0, pageSize) : items, more, null, true);
            }
            case NONE -> new QueryBinding.Page(new ArrayList<>((Collection<?>) result), false, null, true);
        };
    }
}
