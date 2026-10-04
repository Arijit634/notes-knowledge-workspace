package org.notesknowledge.profile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Tiny generated fixtures; no real avatars or external files. */
final class AvatarImages {
    static byte[] image(String format) throws Exception {
        var raster = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        raster.setRGB(0, 0, 0x22aabb);
        var out = new ByteArrayOutputStream();
        ImageIO.write(raster, format, out);
        return out.toByteArray();
    }

    static byte[] pngWithText(String text) throws Exception {
        byte[] png = image("png"), payload = ("Comment\0" + text).getBytes(StandardCharsets.US_ASCII);
        var out = new ByteArrayOutputStream();
        out.write(png, 0, png.length - 12);
        out.write(ByteBuffer.allocate(4).putInt(payload.length).array());
        byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        out.write(type); out.write(payload);
        var crc = new CRC32(); crc.update(type); crc.update(payload);
        out.write(ByteBuffer.allocate(4).putInt((int)crc.getValue()).array());
        out.write(png, png.length - 12, 12);
        return out.toByteArray();
    }

    static byte[] dimensions(int width, int height) throws Exception {
        byte[] png = image("png");
        ByteBuffer.wrap(png, 16, 8).putInt(width).putInt(height);
        var crc = new CRC32(); crc.update(png, 12, 17);
        ByteBuffer.wrap(png, 29, 4).putInt((int)crc.getValue());
        return png;
    }
}
