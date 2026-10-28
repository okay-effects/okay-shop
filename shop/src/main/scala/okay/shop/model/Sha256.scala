package okay.shop.model

/** SHA-256 (FIPS 180-4), written out (Coinpath's `coinpath.text.Sha256`): the workshop's sign-in secrets are kept
 * only as their hash — the same in the page as on the JVM, and nothing asked to compute it */
object Sha256:
  private val K: Array[Int] = Array(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2)

  def hash(message: Array[Byte]): Array[Byte] =
    val h = Array(0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19)
    val bits = message.length.toLong * 8
    val padded = new Array[Byte](((message.length + 9 + 63) / 64) * 64)
    System.arraycopy(message, 0, padded, 0, message.length)
    padded(message.length) = 0x80.toByte
    (0 until 8).foreach(i => padded(padded.length - 1 - i) = (bits >>> (8 * i)).toByte)
    val w = new Array[Int](64)
    padded.indices.by(64).foreach { at =>
      (0 until 16).foreach(i => w(i) = ((padded(at + 4 * i) & 0xff) << 24) | ((padded(at + 4 * i + 1) & 0xff) << 16) |
        ((padded(at + 4 * i + 2) & 0xff) << 8) | (padded(at + 4 * i + 3) & 0xff))
      (16 until 64).foreach { i =>
        val s0 = Integer.rotateRight(w(i - 15), 7) ^ Integer.rotateRight(w(i - 15), 18) ^ (w(i - 15) >>> 3)
        val s1 = Integer.rotateRight(w(i - 2), 17) ^ Integer.rotateRight(w(i - 2), 19) ^ (w(i - 2) >>> 10)
        w(i) = w(i - 16) + s0 + w(i - 7) + s1
      }
      var (a, b, c, d, e, f, g, hh) = (h(0), h(1), h(2), h(3), h(4), h(5), h(6), h(7))
      (0 until 64).foreach { i =>
        val t1 = hh + (Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25)) +
          ((e & f) ^ (~e & g)) + K(i) + w(i)
        val t2 = (Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22)) +
          ((a & b) ^ (a & c) ^ (b & c))
        hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
      }
      Vector(a, b, c, d, e, f, g, hh).zipWithIndex.foreach((v, i) => h(i) += v)
    }
    h.flatMap(v => Array((v >>> 24).toByte, (v >>> 16).toByte, (v >>> 8).toByte, v.toByte))

  def hex(message: Array[Byte]): String = hash(message).map(b => f"${b & 0xff}%02x").mkString
