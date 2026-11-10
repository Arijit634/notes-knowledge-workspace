import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Exact synthetic raster check after the application's safe PNG rewrite. CI only. */
class FixtureImage {
    public static void main(String[] args) throws Exception {
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
