package com.embabel.publisher.spring;

import com.embabel.publisher.annotation.RealmQuery;
import com.embabel.publisher.annotation.RealmType;
import com.embabel.publisher.realm.RealmDefinitionException;
import com.embabel.publisher.realm.TypeIntrospector;
import com.embabel.publisher.realm.TypeModel;
import com.embabel.publisher.where.Op;
import com.embabel.publisher.where.WhereParser;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CriteriaBindingTest {

    @RealmType(name = "Owner")
    record OwnerView(@RealmType.Id Integer id, String lastName, String city, BigDecimal balance) {
    }

    record Search(@RealmQuery.Criterion(property = "lastName", op = Op.PREFIX) String lastNamePrefix,
                  String city, @RealmQuery.Criterion(property = "city") Collection<String> cities, Range<BigDecimal> balance) {
    }

    private final TypeModel owner = TypeIntrospector.introspect(OwnerView.class);

    @Test
    void componentsDeclarePropertyAndOperatorByNameTypeAndAnnotation() {
        CriteriaBinding binding = new CriteriaBinding(Search.class, owner, "test");
        assertThat(binding.declared()).isEqualTo(Map.of("lastName", Set.of(Op.PREFIX), "city", Set.of(Op.EQ, Op.IN), "balance", Set.of(Op.BETWEEN)));
    }

    @Test
    void aConjunctionFillsTheRecordAndNothingElse() {
        CriteriaBinding binding = new CriteriaBinding(Search.class, owner, "test");
        CriteriaBinding.Bound bound = binding.bind(WhereParser.parse(Map.of("and", List.of(
                Map.of("property", "lastName", "op", "prefix", "value", "Mc"),
                Map.of("property", "balance", "op", "between", "value", List.of("1", "2.5"))))));
        assertThat(bound.complete()).isTrue();
        Search search = (Search) bound.criteria();
        assertThat(search.lastNamePrefix()).isEqualTo("Mc");
        assertThat(search.city()).isNull();
        assertThat(search.balance()).isEqualTo(Range.closed(new BigDecimal("1"), new BigDecimal("2.5")));
        assertThat(((Search) binding.bind(null).criteria())).isEqualTo(new Search(null, null, null, null));
    }

    record Unexposed(String telephone) {
    }

    record WrongType(Integer city) {
    }

    record WrongShape(@RealmQuery.Criterion(op = Op.IN) String city) {
    }

    record Unsupported(@RealmQuery.Criterion(property = "lastName", op = Op.GT) String after) {
    }

    record Primitive(int id) {
    }

    @Test
    void startupRefusesWhatTheTypeDoesNotExposeOrCannotTake() {
        assertThatThrownBy(() -> new CriteriaBinding(Unexposed.class, owner, "test")).isInstanceOf(RealmDefinitionException.class)
                .hasMessageContaining("names telephone, which Owner does not expose");
        assertThatThrownBy(() -> new CriteriaBinding(WrongType.class, owner, "test")).hasMessageContaining("city is a String");
        assertThatThrownBy(() -> new CriteriaBinding(WrongShape.class, owner, "test")).hasMessageContaining("in takes a Collection");
        assertThatThrownBy(() -> new CriteriaBinding(Unsupported.class, owner, "test")).hasMessageContaining("does not define gt on a string");
        assertThatThrownBy(() -> new CriteriaBinding(Primitive.class, owner, "test")).hasMessageContaining("primitive");
    }
}
