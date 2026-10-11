package com.embabel.publisher.realm;

import com.embabel.publisher.where.Where;

import java.util.Collection;
import java.util.List;

/**
 * A lookup (§4.2) and what its implementation can take.
 *
 * @param pushesWhere whether the implementation applies a {@code where} itself; when it cannot, the
 *                    library applies it with the reference evaluator, over the records it returns
 * @param sorts       whether the implementation orders by a requested sort
 * @param source      where it was declared, for messages
 */
public record LookupBinding(TypeModel type, String by, int maxKeys, boolean pushesWhere, boolean sorts, Invoker invoker, String source) {

    @FunctionalInterface
    public interface Invoker {
        /**
         * @param keys  parsed keys, in the property's Java type
         * @param where the pushed filter, or {@code null} for none (always {@code null} unless {@code pushesWhere})
         * @param sort  the requested order, empty for none (always empty unless {@code sorts})
         * @return view records
         */
        Collection<?> fetch(Collection<Object> keys, Where where, List<SortKey> sort);
    }
}
