package okay.shop.model

/** a kind of picture accepted: its extension and its content type — the only three (specs/shop.md "Photos") */
enum ImageKind(val ext: String, val contentType: String):
  case Jpeg extends ImageKind("jpg", "image/jpeg")
  case Png extends ImageKind("png", "image/png")
  case Webp extends ImageKind("webp", "image/webp")

object ImageKind:
  def ofExt(ext: String): Option[ImageKind] = values.find(_.ext == ext)

/** a picture accepted: its kind, its size as shown, its bytes with the metadata stripped; its file name the hash of
 * those bytes */
final case class Image(kind: ImageKind, width: Int, height: Int, bytes: Array[Byte]):
  def hash: String = Sha256.hex(bytes).take(20)
  def file: String = s"$hash.${kind.ext}"

/**
 * PICTURES CHECKED AND CLEANED (specs/shop.md "Photos"), pure: a JPEG, PNG or WebP by its magic bytes, its size read
 * from its own header, its metadata (EXIF and GPS, XMP, IPTC, text chunks) dropped without decoding the picture; the
 * JPEG's orientation kept as a minimal EXIF. Anything else refused: `type`, `broken`, `size`
 */
object Images:
  val maxBytes: Int = 5 * 1024 * 1024
  /** the largest side believed (a header saying more is broken) */
  private val maxSide = 30000

  def kind(b: Array[Byte]): Option[ImageKind] =
    def at(i: Int, s: String) = b.length >= i + s.length && s.indices.forall(j => b(i + j) == s.charAt(j).toByte)
    if b.length >= 3 && (b(0) & 0xff) == 0xff && (b(1) & 0xff) == 0xd8 && (b(2) & 0xff) == 0xff then
      Some(ImageKind.Jpeg)
    else if b.length >= 8 && (b(0) & 0xff) == 0x89 && at(1, "PNG\r\n\u001a\n") then Some(ImageKind.Png)
    else if at(0, "RIFF") && at(8, "WEBP") then Some(ImageKind.Webp)
    else None

  def clean(b: Array[Byte], max: Int = maxBytes): Either[String, Image] =
    if b.length > max then Left("size")
    else kind(b) match
      case None => Left("type")
      case Some(k) =>
        val cleaned =
          try
            k match
              case ImageKind.Jpeg => jpeg(b)
              case ImageKind.Png => png(b)
              case ImageKind.Webp => webp(b)
          catch case _: Broken | _: IndexOutOfBoundsException => None
        cleaned.filter((w, h, _) => w > 0 && h > 0 && w <= maxSide && h <= maxSide)
          .map((w, h, out) => Image(k, w, h, out)).toRight("broken")

  /** past the end: broken (checked here: Scala.js does not check an array's bounds in its optimised code) */
  private final class Broken extends RuntimeException("broken", null, false, false)
  private def u8(b: Array[Byte], i: Int): Int = if i < 0 || i >= b.length then throw Broken() else b(i) & 0xff
  private def be16(b: Array[Byte], i: Int): Int = (u8(b, i) << 8) | u8(b, i + 1)
  private def be32(b: Array[Byte], i: Int): Long = (be16(b, i).toLong << 16) | be16(b, i + 2)
  private def le16(b: Array[Byte], i: Int): Int = u8(b, i) | (u8(b, i + 1) << 8)
  private def le24(b: Array[Byte], i: Int): Int = le16(b, i) | (u8(b, i + 2) << 16)
  private def le32(b: Array[Byte], i: Int): Long = le16(b, i).toLong | (le16(b, i + 2).toLong << 16)

  // ---- JPEG: segments walked, the entropy-coded data copied as it is

  private def jpeg(b: Array[Byte]): Option[(Int, Int, Array[Byte])] =
    val out = java.io.ByteArrayOutputStream(b.length)
    out.write(0xff); out.write(0xd8)
    var i = 2
    var w = 0
    var h = 0
    var orientation = 1
    var ended = false
    val kept = java.io.ByteArrayOutputStream(b.length)
    while !ended do
      if u8(b, i) != 0xff then return None
      while u8(b, i) == 0xff do i += 1
      val m = u8(b, i)
      i += 1
      if m == 0xd9 then { kept.write(0xff); kept.write(m); ended = true }
      else if m == 0x01 || (m >= 0xd0 && m <= 0xd7) then { kept.write(0xff); kept.write(m) }
      else
        val len = be16(b, i)
        if len < 2 || i + len > b.length then return None
        val seg = b.slice(i + 2, i + len)
        def isApp(s: String) = seg.length >= s.length && s.indices.forall(j => seg(j) == s.charAt(j).toByte)
        val keep = m match
          case 0xe0 => true // JFIF
          case 0xe1 =>
            if isApp("Exif\u0000\u0000") then orientation = exifOrientation(seg.drop(6)).getOrElse(orientation)
            false
          case 0xe2 => isApp("ICC_PROFILE\u0000")
          case 0xee => isApp("Adobe")
          case 0xfe => false // a comment
          case x if x >= 0xe3 && x <= 0xef => false
          case _ => true
        if m >= 0xc0 && m <= 0xcf && m != 0xc4 && m != 0xc8 && m != 0xcc && h == 0 then
          h = be16(seg, 1)
          w = be16(seg, 3)
        if keep then { kept.write(0xff); kept.write(m); kept.write(b, i, len) }
        i += len
        if m == 0xda then
          // the scan's data, to the next marker that is not a stuffed byte or a restart
          val from = i
          while !(u8(b, i) == 0xff && u8(b, i + 1) != 0x00 && !(u8(b, i + 1) >= 0xd0 && u8(b, i + 1) <= 0xd7) &&
            u8(b, i + 1) != 0xff) do i += 1
          kept.write(b, from, i - from)
    if w == 0 || h == 0 then None
    else
      if orientation != 1 then out.write(orientationExif(orientation))
      out.write(kept.toByteArray)
      val (sw, sh) = if orientation >= 5 && orientation <= 8 then (h, w) else (w, h)
      Some((sw, sh, out.toByteArray))

  /** the orientation tag (0x0112) of an EXIF's first directory, its TIFF header first */
  private def exifOrientation(t: Array[Byte]): Option[Int] =
    try
      val little = u8(t, 0) == 'I' && u8(t, 1) == 'I'
      def s16(i: Int) = if little then le16(t, i) else be16(t, i)
      def s32(i: Int) = if little then le32(t, i) else be32(t, i)
      val ifd = s32(4).toInt
      val n = s16(ifd)
      (0 until n).map(k => ifd + 2 + 12 * k).find(e => s16(e) == 0x0112).map(e => s16(e + 8))
        .filter(o => o >= 1 && o <= 8)
    catch case _: Broken => None

  /** an APP1 EXIF of one tag, the orientation: big-endian TIFF, one directory of one entry */
  private def orientationExif(o: Int): Array[Byte] =
    val body = "Exif\u0000\u0000".getBytes("ISO-8859-1") ++ Array[Int](0x4d, 0x4d, 0, 0x2a, 0, 0, 0, 8, 0, 1,
      0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, o, 0, 0, 0, 0, 0, 0).map(_.toByte)
    val len = body.length + 2
    Array(0xff.toByte, 0xe1.toByte, (len >> 8).toByte, len.toByte) ++ body

  // ---- PNG: chunks kept by their type

  private val pngKept = Set("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM", "gAMA", "iCCP", "sBIT", "sRGB", "bKGD",
    "pHYs", "cICP", "acTL", "fcTL", "fdAT")

  private def png(b: Array[Byte]): Option[(Int, Int, Array[Byte])] =
    val out = java.io.ByteArrayOutputStream(b.length)
    out.write(b, 0, 8)
    var i = 8
    var w = 0
    var h = 0
    var ended = false
    while !ended do
      val len = be32(b, i)
      if len > Int.MaxValue - 12 || i + 12 + len > b.length then return None
      val kind = String(b.slice(i + 4, i + 8), "ISO-8859-1")
      if i == 8 && kind != "IHDR" then return None
      if kind == "IHDR" then
        w = be32(b, i + 8).toInt
        h = be32(b, i + 12).toInt
      if pngKept(kind) then out.write(b, i, 12 + len.toInt)
      i += 12 + len.toInt
      ended = kind == "IEND"
    Some((w, h, out.toByteArray))

  // ---- WebP: RIFF chunks kept by their FourCC, VP8X's flags of what is gone cleared

  private val webpKept = Set("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP")

  private def webp(b: Array[Byte]): Option[(Int, Int, Array[Byte])] =
    val end = math.min(b.length.toLong, 8 + le32(b, 4)).toInt
    val body = java.io.ByteArrayOutputStream(b.length)
    var i = 12
    var w = 0
    var h = 0
    while i + 8 <= end do
      val kind = String(b.slice(i, i + 4), "ISO-8859-1")
      val len = le32(b, i + 4)
      val padded = len + (len & 1)
      if i + 8 + len > end then return None
      val at = i + 8
      kind match
        case "VP8X" =>
          w = le24(b, at + 4) + 1
          h = le24(b, at + 7) + 1
        case "VP8 " if w == 0 =>
          if !(u8(b, at + 3) == 0x9d && u8(b, at + 4) == 0x01 && u8(b, at + 5) == 0x2a) then return None
          w = le16(b, at + 6) & 0x3fff
          h = le16(b, at + 8) & 0x3fff
        case "VP8L" if w == 0 =>
          if u8(b, at) != 0x2f then return None
          val bits = le32(b, at + 1)
          w = (bits & 0x3fff).toInt + 1
          h = ((bits >> 14) & 0x3fff).toInt + 1
        case _ => ()
      if webpKept(kind) then
        val chunk = b.slice(i, math.min(i + 8 + padded, end.toLong).toInt)
        if kind == "VP8X" && chunk.length > 8 then chunk(8) = (chunk(8) & ~0x0c).toByte
        body.write(chunk)
      i = (i + 8 + padded).toInt
    if w == 0 then None
    else
      val rest = body.toByteArray
      val size = rest.length + 4
      Some((w, h, "RIFF".getBytes("ISO-8859-1") ++ Array(size.toByte, (size >> 8).toByte, (size >> 16).toByte,
        (size >> 24).toByte) ++ "WEBP".getBytes("ISO-8859-1") ++ rest))
