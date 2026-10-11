package com.embabel.publisher.value;

/**
 * Unicode simple case folding, which the protocol's {@code ieq}, {@code iprefix} and
 * {@code icontains} compare after (§4.1). Java's per-code-point case mappings are the simple ones,
 * and lower-casing the upper case of a code point is the simple fold for every character except a
 * handful whose fold is neither (Cherokee, which folds to upper case); for those this follows
 * {@link Character}'s tables.
 */
public final class CaseFolding {

    private CaseFolding() {
    }

    public static String fold(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> out.appendCodePoint(Character.toLowerCase(Character.toUpperCase(cp))));
        return out.toString();
    }
}
