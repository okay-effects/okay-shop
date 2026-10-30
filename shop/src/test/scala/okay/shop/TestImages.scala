package okay.shop

/** FICTIONAL PICTURES BY HAND, byte by byte (specs/shop.md "Photos"): each kind's frame around a few bytes of
 * "picture", with the metadata a phone or an editor leaves — EXIF with GPS, XMP, a comment, a PNG's text — to be
 * stripped */
object TestImages:
  private def b(xs: Int*): Array[Byte] = xs.map(_.toByte).toArray
  private def ascii(s: String): Array[Byte] = s.getBytes("ISO-8859-1")
  private def be16(n: Int) = b(n >> 8, n)
  private def be32(n: Int) = b(n >> 24, n >> 16, n >> 8, n)
  private def le32(n: Int) = b(n, n >> 8, n >> 16, n >> 24)
  private def seg(marker: Int, body: Array[Byte]) = b(0xff, marker) ++ be16(body.length + 2) ++ body

  /** an EXIF (little-endian TIFF): the orientation, a GPS directory's pointer, the maker's words */
  def exif(orientation: Int): Array[Byte] =
    ascii("Exif\u0000\u0000") ++ ascii("II") ++ b(0x2a, 0) ++ le32(8) ++ b(3, 0) ++
      b(0x12, 0x01, 3, 0) ++ le32(1) ++ b(orientation, 0, 0, 0) ++
      b(0x25, 0x88, 4, 0) ++ le32(1) ++ le32(50) ++
      b(0x0f, 0x01, 2, 0) ++ le32(8) ++ le32(62) ++ le32(0) ++ ascii("GPS 52.2297N 21.0122E PhoneMaker\u0000")

  /** the picture's own bytes: the scan, with a stuffed 0xFF and a restart inside */
  val scan: Array[Byte] = b(0x12, 0x34, 0xff, 0x00, 0x56, 0xff, 0xd0, 0x78, 0x9a)

  /** a JPEG 30 wide, 20 high, its orientation 6 (turned: shown 20 × 30), with EXIF, XMP, IPTC and a comment */
  val jpeg: Array[Byte] =
    b(0xff, 0xd8) ++ seg(0xe0, ascii("JFIF\u0000") ++ b(1, 1, 0, 0, 1, 0, 1, 0, 0)) ++ seg(0xe1, exif(6)) ++
      seg(0xe1, ascii("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>secret location</x:xmpmeta>")) ++
      seg(0xed, ascii("Photoshop 3.0\u0000IPTC city Warszawa")) ++ seg(0xfe, ascii("a comment: taken at home")) ++
      seg(0xe2, ascii("ICC_PROFILE\u0000") ++ b(1, 1, 9, 9)) ++ seg(0xdb, Array.fill(65)(1.toByte)) ++
      seg(0xc0, b(8) ++ be16(20) ++ be16(30) ++ b(1, 1, 0x11, 0)) ++ seg(0xc4, b(0, 1, 2, 3)) ++
      seg(0xda, b(1, 1, 0, 0, 0x3f, 0)) ++ scan ++ b(0xff, 0xd9)

  private def chunk(kind: String, data: Array[Byte]) = be32(data.length) ++ ascii(kind) ++ data ++ b(0, 0, 0, 0)

  /** a PNG 7 × 5 with text, a time and an EXIF chunk */
  val png: Array[Byte] =
    b(0x89) ++ ascii("PNG\r\n\u001a\n") ++ chunk("IHDR", be32(7) ++ be32(5) ++ b(8, 6, 0, 0, 0)) ++
      chunk("tEXt", ascii("Author\u0000Jan Kowalski")) ++ chunk("eXIf", exif(1).drop(6)) ++
      chunk("pHYs", b(0, 0, 11, 19, 0, 0, 11, 19, 1)) ++ chunk("tIME", b(7, 234, 10, 30, 12, 0, 0)) ++
      chunk("IDAT", b(1, 2, 3, 4, 5)) ++ chunk("IEND", Array.emptyByteArray)

  private def riff(kind: String, data: Array[Byte]) =
    ascii(kind) ++ le32(data.length) ++ data ++ (if data.length % 2 == 1 then b(0) else Array.emptyByteArray)

  /** a WebP 640 × 480 (VP8X: its EXIF and XMP flags set) with EXIF and XMP chunks */
  val webp: Array[Byte] =
    val body = ascii("WEBP") ++ riff("VP8X", b(0x0c, 0, 0, 0) ++ b(639 & 0xff, 639 >> 8, 0, 479 & 0xff, 479 >> 8,
      0)) ++ riff("VP8 ", b(0x10, 0x02, 0, 0x9d, 0x01, 0x2a) ++ b(640 & 0xff, 640 >> 8, 480 & 0xff, 480 >> 8) ++
      b(1, 2, 3)) ++ riff("EXIF", exif(1).drop(6)) ++ riff("XMP ", ascii("<x:xmpmeta>GPS</x:xmpmeta>"))
    ascii("RIFF") ++ le32(body.length) ++ body

  val gif: Array[Byte] = ascii("GIF89a") ++ b(1, 0, 1, 0, 0, 0, 0)
  val svg: Array[Byte] = ascii("""<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""")
  val html: Array[Byte] = ascii("<!doctype html><script>alert(1)</script>")
