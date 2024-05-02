package io.github.nulled.transferguard.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyAndPiiTest {

    @Test
    void displaysRinggit() {
        assertThat(Money.display(new BigDecimal("4800.00"))).isEqualTo("RM 4,800");
        assertThat(Money.display(new BigDecimal("1234567.5"))).isEqualTo("RM 1,234,567.50");
        assertThat(Money.toWire(new BigDecimal("12.3"))).isEqualTo("12.30");
    }

    @Test
    void masksAccountNumbersInText() {
        assertThat(Pii.mask("from 1402-0012-3456 to 1402-0098-76543210"))
                .isEqualTo("from 1402-****-3456 to 1402-****-76543210");
        assertThat(Pii.mask("TXN-20261119-000123")).isEqualTo("TXN-20261119-000123");
        assertThat(Pii.mask(null)).isNull();
    }

    @Test
    void etagsAcceptCommonForms() {
        assertThat(ETags.require("\"3\"")).isEqualTo(3);
        assertThat(ETags.require("W/\"3\"")).isEqualTo(3);
        assertThat(ETags.require("3")).isEqualTo(3);
        assertThat(ETags.of(4)).isEqualTo("\"4\"");
    }
}
