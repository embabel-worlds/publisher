package com.embabel.publisher.realm;

import com.embabel.publisher.where.Op;
import com.embabel.publisher.where.Where;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A lookup (§4.2) and what its implementation can take.
 *
 * @param pushesWhere whether the implementation applies the whole {@code where} itself (it takes a
 *                    {@code Specification}); when it does not, the library applies the filter with the
 *                    reference evaluator over the records it returns
 * @param criteria    the (property, operators) a criteria record carries into the method, or {@code null}
 * @param sorts       whether the implementation orders by a requested sort
 * @param source      where it was declared, for messages
 */
public record LookupBinding(TypeModel type, String by, int maxKeys, boolean pushesWhere, Map<String, Set<Op>> criteria,
                            boolean sorts, Invoker invoker, String source) {

    @FunctionalInterface
    public interface Invoker {
        /**
         * @param keys  parsed keys, in the property's Java type
         * @param where the pushed filter, or {@code null} for none (always {@code null} unless the method takes one)
         * @param sort  the requested order, empty for none (always empty unless {@code sorts})
         * @return view records
         */
        Collection<?> fetch(Collection<Object> keys, Where where, List<SortKey> sort);
    }
}
