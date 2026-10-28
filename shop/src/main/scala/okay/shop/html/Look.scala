package okay.shop.html

import okay.shop.model.*

/** a theme of the storefront's stylesheet: its colours (`:root`'s variables), its background, the rules it adds */
final case class Theme(root: String, background: String, extra: String = "")

/** what a search engine and a link preview read of a storefront: its title, its description, more of the head
 * (structured data) */
final case class Seo(title: String, description: String, head: String = "")

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
  /** the privacy policy: by default the engine's (`Privacy`: a sole trader's RODO policy, its kind's processors) */
  def privacy(site: Site, l: Lang): String = Privacy.page(site, l)
  /** the privacy policy's version: a date, stored with every consent */
  def policy: String = Privacy.version
  /** pages of its own, by their path under the language (`products/`): each language's */
  def pages(at: Lang => (String => At)): Vector[(String, String)] = Vector.empty
