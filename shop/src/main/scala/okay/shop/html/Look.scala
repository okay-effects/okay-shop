package okay.shop.html

import okay.shop.model.*

/** a theme of the storefront's stylesheet: its colours (`:root`'s variables), its background, the rules it adds */
final case class Theme(root: String, background: String, extra: String = "")

/** what a search engine and a link preview read of a storefront: its title, its description, more of the head
 * (structured data) */
final case class Seo(title: String, description: String, head: String = "")

/** THE ASSISTANT'S CHAT on a site's pages (specs/shop.md "The assistant"): where the widget's calls go, and the
 * conversation's focus there (`shop:<id>`, or `mall`) */
final case class Chat(endpoint: String, focus: String)

/**
 * A SITE'S LOOK, its own — the storefront's theme, its words for search engines, its sections between the offers
 * and the contact, its links in the foot, its privacy policy (and that policy's version, kept with every consent),
 * pages of its own (IT's `/products`). The engine draws the rest (`Pages`, `Track`, `Letters`)
 */
trait Look:
  def theme: Theme
  def seo(site: Site)(using Lang): Seo
  /** above the headline (IT's "Systems · diagnosed & solved") */
  def kicker(using Lang): String = ""
  /** between the offers and the contact */
  def sections(at: At)(using Lang): String = ""
  /** after the site's name in the foot: links to its siblings */
  def footLinks(using Lang): String = ""
  /** the workshop's link at the end of every public page's foot (`Pages.staff`): one muted word, not followed */
  def staffLink: Boolean = true
  /** the privacy policy: by default the engine's (`Privacy`: a sole trader's RODO policy, its kind's processors) —
   * `mail` the clients' letters' way as the site's config says it (`Privacy.Mail.of(MAIL_CLIENT, SMTP_HOST)`) */
  def privacy(site: Site, l: Lang, mail: Privacy.Mail): String = Privacy.page(site, l, mail = mail)
  /** the privacy policy's version: a date, stored with every consent */
  def policy: String = Privacy.version
  /** pages of its own, by their path under the language (`products/`): each language's */
  def pages(at: Lang => (String => At)): Vector[(String, String)] = Vector.empty
  /** the assistant's chat on the storefront's and the offers' pages: none (the default) — no widget, the pages as
   * before */
  def chat: Option[Chat] = None

/** THE CHAT TELLS IT IS AN AI (specs/shop.md "The assistant", "Told it is an AI"; EU AI Act art. 50(1)): the widget's
 * launcher and title, the notice above the conversation, the privacy link's words — in the page's language */
object ChatWords:
  final case class Disclosure(label: String, notice: String, privacy: String)

  def disclosure(l: Lang): Disclosure = l match
    case Lang.pl => Disclosure("Asystent AI", "Rozmawiasz z asystentem AI. Ceny, terminy i dane sklepu pochodzą " +
        "bezpośrednio z systemu sklepu; odpowiedzi tekstowe tworzy automat i mogą zawierać błędy. Rezerwację " +
        "potwierdzasz Ty.", "Prywatność czatu")
    case Lang.uk => Disclosure("AI-асистент", "Ви розмовляєте з AI-асистентом. Ціни, час і дані магазину " +
        "надходять безпосередньо із системи магазину; текст відповідей створює автомат, і в ньому можуть бути " +
        "помилки. Запис підтверджуєте ви.", "Конфіденційність чату")
    case Lang.ru => Disclosure("ИИ-ассистент", "Вы разговариваете с ИИ-ассистентом. Цены, время и данные магазина " +
        "берутся напрямую из системы магазина; текст ответов создаёт автомат, и в нём возможны ошибки. Запись " +
        "подтверждаете вы.", "Конфиденциальность чата")
    case Lang.en => Disclosure("AI assistant", "You are talking to an AI assistant. Prices, times and the shop's " +
        "details come straight from the shop's system; the answers' wording is written by an automaton and may " +
        "contain mistakes. You confirm any booking yourself.", "Chat privacy")
