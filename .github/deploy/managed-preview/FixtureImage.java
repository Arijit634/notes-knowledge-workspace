import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Exact synthetic raster check after the application's safe PNG rewrite. CI only. */
class FixtureImage {
    public static void main(String[] args) throws Exception {
        if (args.length == 2) {
            ImageIO.setUseCache(false);
            var images = new java.awt.image.BufferedImage[2];
            for (int i = 0; i < 2; i++) {
                byte[] value;
                try (var input = Files.newInputStream(Path.of(args[i]))) { value = input.readNBytes(5 * 1024 * 1024 + 1); }
                if (value.length > 5 * 1024 * 1024) throw new IllegalStateException("Synthetic image exceeds bound");
                images[i] = ImageIO.read(new ByteArrayInputStream(value));
                if (images[i] == null || images[i].getWidth() != 1200 || images[i].getHeight() != 1200)
                    throw new IllegalStateException("Synthetic raster dimensions changed");
            }
            for (int y = 0; y < 1200; y++) for (int x = 0; x < 1200; x++)
                if (images[0].getRGB(x, y) != images[1].getRGB(x, y))
                    throw new IllegalStateException("Synthetic pixel changed");
            System.out.print("synthetic-raster-verified");
            return;
        }
        if (args.length != 1) throw new IllegalArgumentException("One synthetic image required");
        byte[] bytes;
        try (var input = Files.newInputStream(Path.of(args[0]))) { bytes = input.readNBytes(16385); }
        if (bytes.length > 16384) throw new IllegalStateException("Synthetic image exceeds bound");
        ImageIO.setUseCache(false);
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null || image.getWidth() != 8 || image.getHeight() != 8)
            throw new IllegalStateException("Synthetic raster changed");
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
            if (image.getRGB(x, y) != 0xff4080c0) throw new IllegalStateException("Synthetic pixel changed");
        System.out.print("synthetic-raster-verified");
    }
}
