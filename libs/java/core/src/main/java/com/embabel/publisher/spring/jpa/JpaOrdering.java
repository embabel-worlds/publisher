package com.embabel.publisher.spring.jpa;

/**
 * How a requested order reaches a JPA query. The protocol places nulls as the greatest value: last
 * ascending, first descending (§4.1). Where they sort cannot be left to the store, because with a
 * pushed limit the first N records would then depend on the database.
 * <p>
 * Each starter chooses the strategy its Spring Data line can carry, which is the one difference
 * between the lines this library found by testing them (see the starters' auto-configuration).
 */
public enum JpaOrdering {

    /**
     * A Spring Data {@code Sort} with null handling, passed to the method's {@code Sort} or
     * {@code Pageable}. Spring Data JPA 4 translates null precedence into Criteria queries.
     */
    SORT,

    /**
     * The order is applied by the {@code Specification} the method receives, as a null key and then
     * the value for each sort key, and the method's {@code Sort} or {@code Pageable} is unsorted.
     * Spring Data JPA 3 refuses null precedence in Criteria queries, so a sorting method on that
     * line must also take a {@code Specification}. Spring Data drops the order from count queries.
     */
    SPECIFICATION
}
