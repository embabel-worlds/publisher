package com.embabel.publisher.realm;

/** One key of a requested ordering (§4.1). */
public record SortKey(String property, boolean descending) {
}
