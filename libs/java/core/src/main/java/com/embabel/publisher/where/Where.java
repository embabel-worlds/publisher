package com.embabel.publisher.where;

import java.util.List;

/** The protocol's expression tree (§4.1). Values stay in their wire form. */
public sealed interface Where {

    record Comparison(String property, Op op, Object value) implements Where {
    }

    record And(List<Where> operands) implements Where {
        public And {
            operands = List.copyOf(operands);
        }
    }

    record Or(List<Where> operands) implements Where {
        public Or {
            operands = List.copyOf(operands);
        }
    }

    record Not(Where operand) implements Where {
    }

    /** Existence across a relationship or through a part. Parsed, but evaluated by no one in this version of the library. */
    record Exists(String type, String via, String part, Where where) implements Where {
    }
}
