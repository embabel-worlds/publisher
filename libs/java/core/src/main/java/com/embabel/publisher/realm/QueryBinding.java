package com.embabel.publisher.realm;

import com.embabel.publisher.where.Op;
import com.embabel.publisher.where.Where;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A query (§4.3) and what its implementation can take.
 *
 * @param pushesWhere whether it takes the whole {@code where}, as a {@code Specification}
 * @param criteria    the (property, operators) a criteria record carries, or {@code null}; a query
 *                    can be filtered only through one or the other
 */
public record QueryBinding(TypeModel type, int maxPageSize, boolean pushesWhere, Map<String, Set<Op>> criteria, boolean sorts,
                           Paging paging, Invoker invoker, String source) {

    public enum Paging {
        /** Offset pages with a count: cursors and {@code total}. */
        PAGE,
        /** Offset pages without a count: cursors. */
        SLICE,
        /** One bounded page, no cursor. */
        LIMIT,
        /** Everything the method returns. */
        NONE
    }

    /**
     * One page of view records. {@code total} is {@code null} unless the paging counts.
     * {@code applied} is false when the method could not carry the whole filter (§4.1).
     */
    public record Page(List<?> items, boolean hasNext, Long total, boolean applied) {
    }

    @FunctionalInterface
    public interface Invoker {
        Page query(Where where, List<SortKey> sort, int page, int pageSize);
    }
}
