package okay.shop.worker

import scala.scalajs.js

/** the Worker's chance: `crypto.getRandomValues`, for the refs and secrets (`Ids.from`) */
object Platform:
  def randomInt(): Int =
    val a = new js.typedarray.Int32Array(1)
    js.Dynamic.global.crypto.getRandomValues(a)
    a(0)
