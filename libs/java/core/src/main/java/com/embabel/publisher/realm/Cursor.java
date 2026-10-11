package com.embabel.publisher.realm;

import com.embabel.publisher.protocol.RealmException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The opaque paging cursor (§4.3): the next page's number and the page size the walk began with.
 * Keeping the size in the cursor keeps offsets aligned however the world varies {@code pageSize}.
 */
record Cursor(int page, int pageSize) {

    private static final Pattern FORM = Pattern.compile("o1:([0-9]{1,9}):([0-9]{1,4})");

    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("o1:" + page + ":" + pageSize).getBytes(StandardCharsets.US_ASCII));
    }

    static Cursor decode(String cursor) {
        try {
            Matcher m = FORM.matcher(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
            if (m.matches()) {
                int size = Integer.parseInt(m.group(2));
                if (size >= 1) {
                    return new Cursor(Integer.parseInt(m.group(1)), size);
                }
            }
        }
        catch (IllegalArgumentException e) {
            /* Falls through to the refusal: a cursor this publisher did not issue. */
        }
        throw RealmException.invalid("not a cursor this publisher issued");
    }
}
