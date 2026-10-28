package okay.shop.app

import okay.http.Query
import okay.serverless.cloud.{Request, Response}
import okay.shop.model.*

/**
 * THE WORKER'S FRONT, before any object (specs/sites.md "URLs"): `www.` to the apex, `/` to the visitor's language,
 * the old busi URLs to the new; what is dynamic to the site's Durable Object; the rest is the static pages'
 */
object Front:
  /** the language a visitor reads: the `lang` cookie, else their browser's, else the site's */
  def lang(site: Site, r: Request): Lang =
    Web.cookie(r, "lang").flatMap(Lang.parse).orElse(r.header("accept-language").flatMap(Lang.accepted))
      .getOrElse(site.lang)

  /** a redirect, when the request is for one */
  def redirect(site: Site, r: Request): Option[Response] =
    val query = if r.query.isEmpty then "" else "?" + Query.print(r.query)
    def to(p: String, status: Int = 301) = Some(Response.redirect(p, status).header("Cache-Control", "no-store"))
    lazy val l = lang(site, r).code
    val host = Web.host(r)
    if host.startsWith("www.") then Some(Response.redirect(s"https://${host.stripPrefix("www.")}${r.path}$query", 301))
    else r.path.split('/').toList.filter(_.nonEmpty) match
      case Nil => to(s"/$l/", 302)
      case "s" :: _ :: Nil => to(s"/$l/")
      case "s" :: _ :: "offer" :: key :: _ => to(s"/$l/offer/$key/")
      case "s" :: _ :: "privacy" :: _ => to(s"/$l/privacy/")
      case "s" :: _ :: "track" :: _ => to(s"/$l/track$query")
      case "s" :: _ :: "rachunek" :: ref :: _ => to(s"/$l/track?ref=${Query.encode(ref)}")
      case "s" :: _ => to(s"/$l/")
      case "products" :: Nil if !site.clothing => to(s"/$l/products/")
      // a page's folder without its slash: the page's address is with it (the static pages are folders)
      case parts if !r.path.endsWith("/") && !parts.last.contains('.') && !dynamic(r) => to(s"${r.path}/$query")
      case _ => None

  /** whether the site's Durable Object answers it */
  def dynamic(r: Request): Boolean =
    r.path.split('/').toList.filter(_.nonEmpty) match
      case "api" :: _ => true
      case "workshop" :: "api" :: _ => true
      case l :: ("track" | "receipt") :: Nil => Lang.parse(l).isDefined
      case _ => false
