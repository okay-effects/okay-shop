package okay.shop.html

/** HTML'S ESCAPES: the only way a value reaches a page or a letter — text and attribute values escaped, a JSON string
 * inside a script too */
object Html:
  def escape(s: String): String =
    val b = new StringBuilder(s.length + 16)
    s.foreach {
      case '&' => b.append("&amp;")
      case '<' => b.append("&lt;")
      case '>' => b.append("&gt;")
      case '"' => b.append("&quot;")
      case '\'' => b.append("&#39;")
      case c => b.append(c)
    }
    b.toString

  /** a string's body inside a JSON string literal, safe in a `<script>` too (`<` escaped) */
  def json(s: String): String =
    val b = new StringBuilder(s.length + 16)
    s.foreach {
      case '"' => b.append("\\\"")
      case '\\' => b.append("\\\\")
      case '\n' => b.append("\\n")
      case '\r' => b.append("\\r")
      case '\t' => b.append("\\t")
      case '<' => b.append("\\u003c")
      case '>' => b.append("\\u003e")
      case '&' => b.append("\\u0026")
      case c if c < ' ' => b.append(f"\\u${c.toInt}%04x")
      case c => b.append(c)
    }
    b.toString

  /** text with its line breaks kept: escaped, `\n` as `<br>` */
  def lines(s: String): String = escape(s).replace("\r\n", "\n").replace("\n", "<br>")
