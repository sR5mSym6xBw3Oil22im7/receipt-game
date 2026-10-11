package com.example.receipt.service;

import com.example.receipt.exception.ReceiptException;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * アップロード画像の中身の検査（管理者・公開で共通）。
 * 先頭バイト（マジックナンバー）で JPEG/PNG を判定し、縦横はデコードせずにヘッダーだけ読んで上限を確かめる
 * （小さなファイルが巨大な画素数に展開される「画像ボム」でメモリを使い切られないようにする）。
 */
public final class ImageInspector {
    /** 画素数の上限（約25メガピクセル。一般的なスマートフォンの12〜16MPのレシート写真は通る）。 */
    public static final long MAX_PIXELS = 25_000_000L;
    /** 回転のために全画素をデコードする上限。これを超える画像は回転せず、そのままGeminiに任せる。 */
    public static final long MAX_PIXELS_FOR_ROTATION = 12_000_000L;

    private ImageInspector() {
    }

    /** "image/jpeg" か "image/png"。どちらでもなければ null。 */
    public static String detectType(byte[] image) {
        if (image == null) return null;
        if (image.length >= 3 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8 && (image[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (image.length >= png.length) {
            for (int i = 0; i < png.length; i++) if (image[i] != png[i]) return null;
            return "image/png";
        }
        return null;
    }

    /** {幅, 高さ}。ヘッダーを読めなければ null（デコードはしない）。 */
    public static int[] readSize(byte[] image) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 画素数が上限を超えるなら 413。読めない（壊れた）画像は、ここでは通し、後段（Gemini）の判定に任せる。 */
    public static void requireWithinPixelLimit(byte[] image) {
        int[] size = readSize(image);
        if (size != null && (long) size[0] * size[1] > MAX_PIXELS) {
            throw new ReceiptException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE",
                    "画像の縦横が大きすぎます（約2500万画素まで）。小さくして再度お試しください。");
        }
    }

    /** 回転のためにデコードしてよい大きさか。 */
    public static boolean canDecodeForRotation(byte[] image) {
        int[] size = readSize(image);
        return size != null && (long) size[0] * size[1] <= MAX_PIXELS_FOR_ROTATION;
    }
}
