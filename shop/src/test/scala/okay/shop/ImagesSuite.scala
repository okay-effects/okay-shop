package okay.shop

import okay.shop.model.{ImageKind, Images}

/** PICTURES CHECKED AND CLEANED (specs/shop.md "Photos"): each kind's metadata gone, the picture's bytes kept, the
 * JPEG's orientation kept, the sizes read; anything else refused */
class ImagesSuite extends munit.FunSuite:
  private def has(all: Array[Byte], part: String): Boolean = String(all, "ISO-8859-1").contains(part)
  private def has(all: Array[Byte], part: Array[Byte]): Boolean = all.indexOfSlice(part.toSeq) >= 0

  test("a JPEG: EXIF with GPS, XMP, IPTC and the comment gone; ICC and the scan kept; the orientation kept") {
    val i = Images.clean(TestImages.jpeg).toOption.get
    assertEquals((i.kind, i.width, i.height), (ImageKind.Jpeg, 20, 30))
    for gone <- Seq("GPS", "PhoneMaker", "xmpmeta", "Warszawa", "a comment", "Photoshop") do
      assert(!has(i.bytes, gone), gone)
    assert(has(i.bytes, "JFIF") && has(i.bytes, "ICC_PROFILE") && has(i.bytes, TestImages.scan))
    // the one EXIF left: the orientation alone
    assertEquals(String(i.bytes.slice(2, 14), "ISO-8859-1"), "ÿá\u0000\"Exif\u0000\u0000MM")
    assertEquals(i.bytes(31).toInt, 6)
    assert(i.bytes.endsWith(Seq(0xff.toByte, 0xd9.toByte)))
    // cleaned again: the same bytes
    assertEquals(Images.clean(i.bytes).toOption.get.bytes.toVector, i.bytes.toVector)
    assertEquals(i.file, i.hash + ".jpg")
    assertEquals(i.hash.length, 20)
  }

  test("a JPEG upright: no EXIF at all") {
    // the orientation's value: after "Exif\0\0", the TIFF header, the count, the tag, its type and count
    val at = TestImages.jpeg.indexOfSlice("Exif".getBytes.toSeq) + 6 + 8 + 2 + 8
    val upright = TestImages.jpeg.patch(at, Seq(1.toByte), 1)
    val i = Images.clean(upright).toOption.get
    assert(!has(i.bytes, "Exif"))
    assertEquals((i.width, i.height), (30, 20))
  }

  test("a PNG: text, EXIF and time gone; the picture's chunks kept") {
    val i = Images.clean(TestImages.png).toOption.get
    assertEquals((i.kind, i.width, i.height), (ImageKind.Png, 7, 5))
    for gone <- Seq("tEXt", "Kowalski", "eXIf", "GPS", "tIME") do assert(!has(i.bytes, gone), gone)
    for kept <- Seq("IHDR", "pHYs", "IDAT", "IEND") do assert(has(i.bytes, kept), kept)
  }

  test("a WebP: EXIF and XMP gone, their flags cleared, the RIFF size right") {
    val i = Images.clean(TestImages.webp).toOption.get
    assertEquals((i.kind, i.width, i.height), (ImageKind.Webp, 640, 480))
    for gone <- Seq("EXIF", "XMP", "GPS") do assert(!has(i.bytes, gone), gone)
    assertEquals(i.bytes(20) & 0x0c, 0)
    val size = (i.bytes(4) & 0xff) | (i.bytes(5) & 0xff) << 8 | (i.bytes(6) & 0xff) << 16
    assertEquals(size, i.bytes.length - 8)
  }

  test("refused: GIF, SVG, HTML, empty, cut short, too large") {
    for (bytes, why) <- Seq(TestImages.gif -> "type", TestImages.svg -> "type", TestImages.html -> "type",
      Array.emptyByteArray -> "type", TestImages.jpeg.take(40) -> "broken", TestImages.png.take(30) -> "broken",
      TestImages.webp.take(25) -> "broken") do assertEquals(Images.clean(bytes), Left(why))
    assertEquals(Images.clean(TestImages.jpeg, max = 100), Left("size"))
  }
