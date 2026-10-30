package okay.shop.html

import okay.shop.model.*

/**
 * THE SITE'S PICTURES on its storefront (specs/shop.md "Photos"): the logo in the header's mark, "Our work" — a grid of
 * the gallery's photos, lazy, each with its size and its words — and their style, all only when the site has them:
 * a site without pictures is drawn byte for byte as before
 */
object Gallery:
  private def e(s: String) = Html.escape(s)
  private val title = T("Наши работы", "Nasze prace", "Our work", "Наші роботи")

  /** the logo before the logo text; its words the site's name only when there is no logo text */
  def logo(site: Site): String = site.logo.fold("") { p =>
    val alt = if site.logoText.trim.nonEmpty then "" else site.name
    s"""<img class="logo" src="${e(p.src)}" width="${p.width}" height="${p.height}" alt="${e(alt)}">"""
  }

  /** the section, after the look's own: nothing without photos */
  def block(site: Site)(using l: Lang): String =
    if site.gallery.isEmpty then "" else
      val shots = site.gallery.map { p =>
        val alt = if p.alt(l).trim.nonEmpty then p.alt(l) else p.alt(site.lang)
        s"""<a href="${e(p.src)}" target="_blank" rel="noopener"><img src="${e(p.src)}" width="${p.width}" height="${
          p.height}" alt="${e(alt)}" loading="lazy" decoding="async"></a>"""
      }.mkString("\n        ")
      s"""<section class="gallery" id="gallery">
      <p class="label">${e(title(l))}</p>
      <div class="shots">
        $shots
      </div>
    </section>
    """

  /** the pictures' style, added to the storefront's only when the site has a logo or photos */
  def style(site: Site): String =
    if site.logo.isEmpty && site.gallery.isEmpty then "" else """
    .mark .logo { height: 40px; width: auto; max-width: 160px; object-fit: contain; vertical-align: middle;
      margin-right: 10px; border-radius: 6px; }
    .gallery { margin-top: 9vh; }
    .shots { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px;
      margin-top: 14px; }
    .shots a { display: block; border-radius: 12px; overflow: hidden; border: 1px solid var(--line); }
    .shots img { display: block; width: 100%; height: auto; aspect-ratio: 1 / 1; object-fit: cover; }
"""

  /** the quote form's photos (up to 3), when the host keeps them; checked again by the page's script and the server */
  def photoField(site: Site)(using l: Lang): String =
    if !site.photos then "" else s"""
          <label class="fld"><span>${e(T("Фото (до 3: JPEG, PNG, WebP, до 5 МБ)", "Zdjęcia (do 3: JPEG, PNG, WebP, " +
            "do 5 MB)", "Photos (up to 3: JPEG, PNG, WebP, 5 MB each)", "Фото (до 3: JPEG, PNG, WebP, до 5 МБ)")(l))
            }</span><input type="file" name="photos" accept="image/jpeg,image/png,image/webp" multiple
            data-photos="3"></label>"""
