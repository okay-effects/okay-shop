package okay.shop

import okay.shop.model.Images

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import javax.imageio.ImageIO

/** REAL PICTURES (the JDK's encoders) cleaned (specs/shop.md "Photos"): metadata put in and taken out, the decoded
 * picture the same pixel for pixel */
class RealImagesSuite extends munit.FunSuite:
  private def drawn(w: Int, h: Int): BufferedImage =
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setColor(Color(250, 240, 230)); g.fillRect(0, 0, w, h)
    g.setColor(Color(200, 80, 40)); g.fillOval(w / 4, h / 4, w / 2, h / 2)
    g.dispose()
    img
  private def encoded(img: BufferedImage, kind: String): Array[Byte] =
    val out = ByteArrayOutputStream()
    ImageIO.write(img, kind, out)
    out.toByteArray
  private def pixels(b: Array[Byte]): Vector[Int] =
    val img = ImageIO.read(ByteArrayInputStream(b))
    (for y <- 0 until img.getHeight; x <- 0 until img.getWidth yield img.getRGB(x, y)).toVector

  test("a JPEG with an EXIF of GPS put in after its start: the EXIF gone, the picture decoded the same") {
    val plain = encoded(drawn(64, 48), "jpg")
    val exif = TestImages.exif(1)
    val seg = Array(0xff.toByte, 0xe1.toByte, ((exif.length + 2) >> 8).toByte, (exif.length + 2).toByte) ++ exif
    val tagged = plain.take(2) ++ seg ++ plain.drop(2)
    val i = Images.clean(tagged).toOption.get
    assertEquals((i.width, i.height), (64, 48))
    assert(!String(i.bytes, "ISO-8859-1").contains("GPS"))
    assertEquals(pixels(i.bytes), pixels(plain))
  }

  test("a PNG with a text chunk put in: the text gone, the picture decoded the same") {
    val plain = encoded(drawn(33, 21), "png")
    val text = "Author\u0000Jan Kowalski".getBytes("ISO-8859-1")
    val chunk = java.nio.ByteBuffer.allocate(12 + text.length).putInt(text.length).put("tEXt".getBytes)
      .put(text).putInt(0).array()
    val tagged = plain.take(33) ++ chunk ++ plain.drop(33)
    val i = Images.clean(tagged).toOption.get
    assertEquals((i.width, i.height), (33, 21))
    assert(!String(i.bytes, "ISO-8859-1").contains("Kowalski"))
    assertEquals(pixels(i.bytes), pixels(plain))
  }
