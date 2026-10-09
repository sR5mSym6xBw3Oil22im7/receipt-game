package com.example.receipt.monster.engine;

/** 個人情報を取り除けなかったことを表す。内容はメッセージにもログにも含めない。 */
public class PersonalInfoException extends RuntimeException {
    public PersonalInfoException() {
        super("個人情報の確認ができませんでした。");
    }

    public PersonalInfoException(Throwable cause) {
        super("個人情報の確認ができませんでした。", cause);
    }
}
