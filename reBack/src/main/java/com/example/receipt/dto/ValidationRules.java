package com.example.receipt.dto;

/** リクエストDTOの制約に使う共通の上限値（DBの列幅と合わせる）。 */
public final class ValidationRules {
    public static final int MAX_LINES = 1000;
    public static final int MAX_LINE_LENGTH = 500;
    public static final int MAX_ITEMS = 200;
    public static final int MAX_NAME = 255;
    public static final int MAX_CATEGORY = 50;
    public static final int MAX_PAYMENT = 100;
    public static final int MAX_RECEIPT_NUMBER = 100;
    public static final long MAX_AMOUNT = 1_000_000_000L;
    /** PostgreSQLのtextはNUL文字を保存できない。 */
    public static final String NO_NUL = "[^\\u0000]*";

    private ValidationRules() {
    }
}
