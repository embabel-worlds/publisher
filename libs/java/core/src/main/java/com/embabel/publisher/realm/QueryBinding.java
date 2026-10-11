package com.embabel.publisher.realm;

import com.embabel.publisher.where.Where;

import java.util.List;

/** A query (§4.3) and what its implementation can take. */
public record QueryBinding(TypeModel type, int maxPageSize, boolean pushesWhere, boolean sorts, Paging paging, Invoker invoker, String source) {

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

    /** One page of view records. {@code total} is {@code null} unless the paging counts. */
    public record Page(List<?> items, boolean hasNext, Long total) {
    }

    @FunctionalInterface
    public interface Invoker {
        Page query(Where where, List<SortKey> sort, int page, int pageSize);
    }
}
