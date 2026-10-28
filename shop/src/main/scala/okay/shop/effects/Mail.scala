package okay.shop.effects

import okay.scala3.*

/** A LETTER of ours: its subject, its HTML and its text; who sends it is the handler's (`MAIL_FROM`) */
final case class Note(subject: String, html: String, text: String, replyTo: Option[String] = None)

/** LETTERS: to the owner, to a staff member (both verified destinations: `send_email`), to a client (Brevo or
 * Email Service). Whether it went: a letter is never the order, a failure is logged and the program goes on */
enum Mail[X]:
  case ToOwner(note: Note) extends Mail[Boolean]
  case ToStaff(to: String, note: Note) extends Mail[Boolean]
  case ToClient(to: String, note: Note) extends Mail[Boolean]

object Mail:
  def toOwner[R[_]](n: Note)(using In[Mail, R]): Boolean ! R = perform[Mail](ToOwner(n))
  def toStaff[R[_]](to: String, n: Note)(using In[Mail, R]): Boolean ! R = perform[Mail](ToStaff(to, n))
  def toClient[R[_]](to: String, n: Note)(using In[Mail, R]): Boolean ! R = perform[Mail](ToClient(to, n))

  /** tests: every letter kept — who to (`owner` for the owner), and the note — each answered `sent` */
  def kept[R[_], Rest[_], A](sent: Boolean = true)(p: A ! R)(using Split[Mail, R, Rest])
    : (Vector[(String, Note)], A) ! Rest =
    handle(p, Handler.stateful[Mail, Rest, A, (Vector[(String, Note)], A), Vector[(String, Note)]](Vector.empty)(
      (ls, a) => pure((ls, a)))([X] => (op: Mail[X], cell: Cell[Vector[(String, Note)]]) => op match
        case ToOwner(n) => cell.set(cell.get :+ ("owner" -> n)); sent
        case ToStaff(to, n) => cell.set(cell.get :+ (to -> n)); sent
        case ToClient(to, n) => cell.set(cell.get :+ (to -> n)); sent))
