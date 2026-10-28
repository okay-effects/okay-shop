package okay.shop.effects

import okay.scala3.*

/** A PERSON, NOT A BOT: Turnstile's token checked */
enum Captcha[X]:
  case Verify(token: String, ip: String) extends Captcha[Boolean]

object Captcha:
  def verify[R[_]](token: String, ip: String)(using In[Captcha, R]): Boolean ! R = perform[Captcha](Verify(token, ip))

  /** tests: every token answered `ok` */
  def always[R[_], Rest[_], A](ok: Boolean)(p: A ! R)(using Split[Captcha, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Captcha, Rest, A, A](pure)([X] => (op: Captcha[X]) => op match
      case Verify(_, _) => ok))
