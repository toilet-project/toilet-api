package com.example.toiletapi.photo;
import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
class PhotoProcessorTest {
    @Test void actualChildProcessProducesOnlyWebpAndRejectsNonImage() throws Exception {
        String python=System.getProperty("os.name").startsWith("Windows")?"python":"python3";
        var processor=new PhotoProcessor(new PhotoSettings(true,null,null,null,null,python,Path.of("scripts/normalize_profile_photo.py").toAbsolutePath().toString()));
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(400,300,BufferedImage.TYPE_INT_RGB),"png",output);
        byte[] webp=processor.convert(output.toByteArray());assertEquals("WEBP",new String(webp,8,4));
        assertTrue(webp.length<100_000);assertThrows(IllegalStateException.class,()->processor.convert("<html>no image</html>".getBytes()));
    }
}
