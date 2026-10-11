package com.embabel.publisher.value;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValuesTest {

    @Test
    void integersAreNumbersWhileTheyFitInTwoToTheFiftyThree() {
        assertThat(Values.toWire(42)).isEqualTo(42L);
        assertThat(Values.toWire(9007199254740992L)).isEqualTo(9007199254740992L);
        assertThat(Values.toWire(9007199254740993L)).isEqualTo("9007199254740993");
        assertThat(Values.toWire(-9007199254740993L)).isEqualTo("-9007199254740993");
        assertThat(Values.toWire(new BigInteger("123456789012345678901234567890"))).isEqualTo("123456789012345678901234567890");
    }

    @Test
    void decimalsArePlainStringsKeepingTheirScale() {
        assertThat(Values.toWire(new BigDecimal("1234.50"))).isEqualTo("1234.50");
        assertThat(Values.toWire(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(Values.toWire(new BigDecimal("1E-7"))).isEqualTo("0.0000001");
    }

    @Test
    void temporalValuesAreIsoStrings() {
        assertThat(Values.toWire(LocalDate.of(2026, 10, 10))).isEqualTo("2026-10-10");
        assertThat(Values.toWire(Instant.parse("2026-10-10T09:30:00Z"))).isEqualTo("2026-10-10T09:30:00Z");
        assertThat(Values.toWire(OffsetDateTime.of(2026, 10, 10, 9, 30, 0, 0, ZoneOffset.ofHours(2)))).isEqualTo("2026-10-10T09:30:00+02:00");
        assertThat(Values.toWire(List.of(1, 2))).isEqualTo(List.of(1L, 2L));
    }

    @Test
    void integerKeysParseOnlyInCanonicalForm() {
        assertThat(Values.parseKey("42", ValueType.INTEGER, Integer.class)).contains(42);
        assertThat(Values.parseKey("-7", ValueType.INTEGER, Long.class)).contains(-7L);
        for (String notCanonical : List.of("042", "42.0", "4.2E1", "+42", " 42", "", "-0", "abc")) {
            assertThat(Values.parseKey(notCanonical, ValueType.INTEGER, Integer.class)).as(notCanonical).isEmpty();
        }
        assertThat(Values.parseKey("0", ValueType.INTEGER, Integer.class)).contains(0);
    }

    @Test
    void aKeyOutOfTheJavaTypesRangeIsMissingNotAnError() {
        assertThat(Values.parseKey("2147483648", ValueType.INTEGER, Integer.class)).isEmpty();
        assertThat(Values.parseKey("2147483648", ValueType.INTEGER, Long.class)).contains(2147483648L);
    }

    @Test
    void decimalKeysAreCanonicalWithoutTrailingZeros() {
        assertThat(Values.canonicalKeyOfWire("1234.50", ValueType.DECIMAL)).isEqualTo("1234.5");
        assertThat(Values.canonicalKeyOfWire("100", ValueType.DECIMAL)).isEqualTo("100");
        assertThat(Values.canonicalKeyOfWire("0.00", ValueType.DECIMAL)).isEqualTo("0");
        assertThat(Values.parseKey("1234.5", ValueType.DECIMAL, BigDecimal.class)).contains(new BigDecimal("1234.5"));
        assertThat(Values.parseKey("1234.50", ValueType.DECIMAL, BigDecimal.class)).isEmpty();
        assertThat(Values.parseKey("1E+2", ValueType.DECIMAL, BigDecimal.class)).isEmpty();
    }

    @Test
    void integerWireValuesMatchTheirKeyByCanonicalString() {
        assertThat(Values.canonicalKeyOfWire(6L, ValueType.INTEGER)).isEqualTo("6");
        assertThat(Values.canonicalKeyOfWire("9007199254740993", ValueType.INTEGER)).isEqualTo("9007199254740993");
    }

    @Test
    void otherKeyTypes() {
        assertThat(Values.parseKey("true", ValueType.BOOLEAN, Boolean.class)).contains(true);
        assertThat(Values.parseKey("TRUE", ValueType.BOOLEAN, Boolean.class)).isEmpty();
        assertThat(Values.parseKey("2026-10-10", ValueType.DATE, LocalDate.class)).contains(LocalDate.of(2026, 10, 10));
        assertThat(Values.parseKey("2026-13-10", ValueType.DATE, LocalDate.class)).isEmpty();
        assertThat(Values.parseKey("anything at all", ValueType.STRING, String.class)).contains("anything at all");
        assertThat(Values.parseKey("RED", ValueType.ENUM, Colour.class)).contains(Colour.RED);
        assertThat(Values.parseKey("MAUVE", ValueType.ENUM, Colour.class)).isEqualTo(Optional.empty());
    }

    @Test
    void decimalsAreStringsNeverJsonNumbers() {
        assertThat(Values.fromWire("12.50", BigDecimal.class)).isEqualTo(new BigDecimal("12.50"));
        assertThatThrownBy(() -> Values.fromWire("1e3", BigDecimal.class)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> Values.fromWire(new BigDecimal("1.5"), BigDecimal.class)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> Values.fromWire(15L, BigDecimal.class)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> Values.fromWire("12", LocalDate.class)).isInstanceOf(InvalidValueException.class);
        assertThat(Values.fromWire("12", Integer.class)).isEqualTo(12);
        assertThat(Values.fromWire(12L, Integer.class)).isEqualTo(12);
    }

    @Test
    void caseFoldingIsSimpleFolding() {
        assertThat(CaseFolding.fold("MÜLLER")).isEqualTo("müller");
        assertThat(CaseFolding.fold("ΟΔΟΣ")).isEqualTo(CaseFolding.fold("οδος"));
        assertThat(CaseFolding.fold("ς")).isEqualTo("σ");
    }

    enum Colour { RED, GREEN }
}
