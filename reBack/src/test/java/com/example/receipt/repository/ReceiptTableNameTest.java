package com.example.receipt.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptTableNameTest {
    @Test
    void generatedNameIsSafeAndUnique() {
        String first = ReceiptTableName.generate();
        String second = ReceiptTableName.generate();

        assertThat(ReceiptTableName.isSafe(first)).isTrue();
        assertThat(ReceiptTableName.isSafe(second)).isTrue();
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void unsafeIdentifierIsRejected() {
        assertThat(ReceiptTableName.isSafe("receipt_x;drop table users")).isFalse();
        assertThat(ReceiptTableName.isSafe("receipt_../../etc/passwd")).isFalse();
    }
}
