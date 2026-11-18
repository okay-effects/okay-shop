package okay.shop.commands.bench

import okay.shop.commands.{Booked, Command, Desk}
import okay.shop.effects.Ids
import okay.shop.model.{Lang, Method, Reports, T}
import scala.util.Random

/**
 * FICTIONAL DESKS AND COMMANDS for the reader's training texts (specs/commands.md A2): every desk another shop —
 * its trade, masters, hours, step, horizon, today, bookings — so a reader has to read the desk before it. All
 * names are invented from lists of common first names and surnames; nobody real, no real business.
 * A desk is a function of its seed, so a text keeps the seed and not the desk.
 */
object Gen:
  private def t(ru: String, pl: String, en: String, uk: String) = T(ru, pl, en, uk)

  private val trades: Vector[Vector[(String, T)]] = Vector(
    Vector("haircut" -> t("Стрижка", "Strzyżenie", "Haircut", "Стрижка"),
      "styling" -> t("Укладка", "Modelowanie", "Styling", "Укладання"),
      "color" -> t("Окрашивание", "Koloryzacja", "Colouring", "Фарбування"),
      "beard" -> t("Борода", "Broda", "Beard trim", "Борода"),
      "kids" -> t("Детская стрижка", "Strzyżenie dziecięce", "Kids' haircut", "Дитяча стрижка"),
      "perm" -> t("Завивка", "Trwała", "Perm", "Завивка"),
      "highlights" -> t("Мелирование", "Pasemka", "Highlights", "Мелірування")),
    Vector("manicure" -> t("Маникюр", "Manicure", "Manicure", "Манікюр"),
      "pedicure" -> t("Педикюр", "Pedicure", "Pedicure", "Педикюр"),
      "gel" -> t("Гель-лак", "Lakier hybrydowy", "Gel polish", "Гель-лак"),
      "removal" -> t("Снятие покрытия", "Zdjęcie hybrydy", "Polish removal", "Зняття покриття"),
      "extension" -> t("Наращивание ногтей", "Przedłużanie paznokci", "Nail extensions", "Нарощування нігтів")),
    Vector("brows" -> t("Коррекция бровей", "Regulacja brwi", "Brow shaping", "Корекція брів"),
      "lashes" -> t("Наращивание ресниц", "Przedłużanie rzęs", "Lash extensions", "Нарощування вій"),
      "facial" -> t("Чистка лица", "Oczyszczanie twarzy", "Facial", "Чистка обличчя"),
      "makeup" -> t("Макияж", "Makijaż", "Make-up", "Макіяж"),
      "waxing" -> t("Депиляция", "Depilacja", "Waxing", "Депіляція")),
    Vector("classic" -> t("Классический массаж", "Masaż klasyczny", "Classic massage", "Класичний масаж"),
      "back" -> t("Массаж спины", "Masaż pleców", "Back massage", "Масаж спини"),
      "sport" -> t("Спортивный массаж", "Masaż sportowy", "Sports massage", "Спортивний масаж"),
      "relax" -> t("Расслабляющий массаж", "Masaż relaksacyjny", "Relaxing massage", "Розслаблювальний масаж"),
      "feet" -> t("Массаж стоп", "Masaż stóp", "Foot massage", "Масаж стоп")),
    Vector("oil" -> t("Замена масла", "Wymiana oleju", "Oil change", "Заміна мастила"),
      "tyres" -> t("Шиномонтаж", "Wymiana opon", "Tyre change", "Шиномонтаж"),
      "diagnostics" -> t("Диагностика", "Diagnostyka", "Diagnostics", "Діагностика"),
      "brakes" -> t("Тормоза", "Hamulce", "Brakes", "Гальма"),
      "aircon" -> t("Кондиционер", "Klimatyzacja", "Air conditioning", "Кондиціонер"),
      "wash" -> t("Мойка", "Mycie", "Car wash", "Миття")),
    Vector("grooming" -> t("Груминг", "Strzyżenie psa", "Dog grooming", "Грумінг"),
      "bath" -> t("Купание", "Kąpiel", "Bath", "Купання"),
      "claws" -> t("Стрижка когтей", "Obcinanie pazurów", "Nail clipping", "Підрізання кігтів"),
      "cat" -> t("Стрижка кошки", "Strzyżenie kota", "Cat grooming", "Стрижка кота")),
    Vector("math" -> t("Математика", "Matematyka", "Maths", "Математика"),
      "english" -> t("Английский", "Angielski", "English", "Англійська"),
      "polish" -> t("Польский", "Polski", "Polish", "Польська"),
      "piano" -> t("Фортепиано", "Fortepian", "Piano", "Фортепіано"),
      "guitar" -> t("Гитара", "Gitara", "Guitar", "Гітара")),
    Vector("consult" -> t("Консультация", "Konsultacja", "Consultation", "Консультація"),
      "tattoo" -> t("Татуировка", "Tatuaż", "Tattoo", "Татуювання"),
      "piercing" -> t("Пирсинг", "Piercing", "Piercing", "Пірсинг"),
      "correction" -> t("Коррекция", "Korekta", "Touch-up", "Корекція")),
    Vector("portrait" -> t("Портрет", "Portret", "Portrait", "Портрет"),
      "family" -> t("Семейная съёмка", "Sesja rodzinna", "Family session", "Сімейна зйомка"),
      "documents" -> t("Фото на документы", "Zdjęcia do dokumentów", "ID photos", "Фото на документи"),
      "product" -> t("Предметная съёмка", "Zdjęcia produktów", "Product shots", "Предметна зйомка")))

  /** the masters as a site writes them: the name, the key */
  private val latinMasters = Vector("Tomasz", "Iryna", "Natalia", "Bartek", "Julia", "Wiktor", "Daria", "Karol",
    "Zofia", "Igor", "Sylwia", "Dawid", "Halina", "Oleh", "Renata", "Kamil", "Lena", "Artur", "Beata", "Roman",
    "Nina", "Filip", "Aneta", "Denys", "Vira", "Hubert", "Tetiana", "Jan", "Ula", "Emil").map(n => n -> n.toLowerCase)
  private val cyrillicMasters = Vector("Тарас" -> "taras", "Ірина" -> "iryna", "Наталя" -> "natalia",
    "Богдан" -> "bohdan", "Юлія" -> "yulia", "Віктор" -> "viktor", "Дарина" -> "daryna", "Ігор" -> "ihor",
    "Роман" -> "roman", "Ніна" -> "nina", "Денис" -> "denys", "Віра" -> "vira", "Тетяна" -> "tetiana",
    "Леся" -> "lesia", "Максим" -> "maksym", "Соломія" -> "solomia")

  /** first names with whether a woman's; surnames in a man's and a woman's form */
  private final case class People(women: Vector[String], men: Vector[String], families: Vector[(String, String)])
  private def same(names: String*): Vector[(String, String)] = names.toVector.map(n => n -> n)
  private def ova(names: String*): Vector[(String, String)] = names.toVector.map(n => n -> (n + "а"))
  private def ski(names: String*): Vector[(String, String)] = names.toVector.map(n => n -> (n.dropRight(1) + "a"))

  private val people: Map[Lang, People] = Map(
    Lang.ru -> People(
      Vector("Наташа", "Лена", "Таня", "Ира", "Юля", "Даша", "Настя", "Вика", "Марина", "Галина", "Людмила", "Вера"),
      Vector("Саша", "Дима", "Сергей", "Миша", "Костя", "Паша", "Олег", "Виктор", "Женя", "Николай", "Артём"),
      ova("Смирнов", "Кузнецов", "Попов", "Соколов", "Лебедев", "Козлов", "Новиков", "Морозов", "Волков", "Зайцев",
        "Павлов", "Орлов", "Белов", "Громов")),
    Lang.uk -> People(
      Vector("Наталка", "Галя", "Іра", "Юля", "Даринка", "Соломія", "Леся", "Тетяна", "Марічка", "Христина", "Надія"),
      Vector("Тарас", "Богдан", "Сашко", "Назар", "Остап", "Василь", "Микола", "Юрко", "Дмитро", "Павло"),
      same("Шевченко", "Бондаренко", "Ткаченко", "Кравченко", "Коваленко", "Бойко", "Ковальчук", "Савчук", "Гнатюк",
        "Лисенко", "Мороз", "Поліщук") ++ Vector("Левицький" -> "Левицька", "Яворський" -> "Яворська")),
    Lang.pl -> People(
      Vector("Ewa", "Agnieszka", "Magda", "Ola", "Zosia", "Basia", "Marta", "Iwona", "Dorota", "Justyna", "Monika",
        "Gosia"),
      Vector("Tomek", "Krzysztof", "Paweł", "Michał", "Jacek", "Darek", "Bartek", "Łukasz", "Grzegorz", "Staszek"),
      same("Lis", "Mazur", "Kowalczyk", "Krawczyk", "Pawlak", "Sikora", "Baran", "Duda", "Wróbel") ++
        ski("Zieliński", "Szymański", "Lewandowski", "Kamiński", "Dąbrowski", "Kozłowski", "Jankowski")),
    Lang.en -> People(
      Vector("Sophie", "Olivia", "Grace", "Lucy", "Chloe", "Hannah", "Megan", "Ruth", "Zoe", "Alice", "Kate", "Jess"),
      Vector("Jack", "Tom", "Ben", "Harry", "Sam", "Luke", "Oliver", "George", "Dan", "Max"),
      same("Brown", "Taylor", "Wilson", "Evans", "Walker", "Wright", "Green", "Hall", "Wood", "Clarke", "Hughes",
        "Turner", "Cooper", "Ward")))

  private val latin: Map[Char, String] = Map('а' -> "a", 'б' -> "b", 'в' -> "v", 'г' -> "h", 'ґ' -> "g", 'д' -> "d",
    'е' -> "e", 'є' -> "ie", 'ж' -> "zh", 'з' -> "z", 'и' -> "y", 'і' -> "i", 'ї' -> "i", 'й' -> "i", 'к' -> "k",
    'л' -> "l", 'м' -> "m", 'н' -> "n", 'о' -> "o", 'п' -> "p", 'р' -> "r", 'с' -> "s", 'т' -> "t", 'у' -> "u",
    'ф' -> "f", 'х' -> "kh", 'ц' -> "ts", 'ч' -> "ch", 'ш' -> "sh", 'щ' -> "shch", 'ь' -> "", 'ю' -> "iu", 'я' -> "ia")

  /** a Ukrainian name in Latin letters, as a shop's page often has it */
  private def toLatin(name: String): String = name.split(' ').map { w =>
    val s = w.toLowerCase.flatMap(c => latin.getOrElse(c, c.toString))
    s.take(1).toUpperCase + s.drop(1)
  }.mkString(" ")

  private def pick[A](r: Random, xs: Vector[A]): A = xs(r.nextInt(xs.size))
  private def weighted[A](r: Random, xs: (A, Int)*): A =
    var at = r.nextInt(xs.map(_._2).sum)
    xs.find { (_, w) => at -= w; at < 0 }.get._1

  /** a person's name in a language: the first name, and four times in ten a surname too */
  def person(r: Random, l: Lang, full: Double = 0.4): String =
    val p = people(l)
    val woman = r.nextInt(100) < 60
    val first = pick(r, if woman then p.women else p.men)
    val (his, hers) = pick(r, p.families)
    if r.nextDouble() < full then s"$first ${if woman then hers else his}" else first

  /** a client as a shop's list has them: most with a surname; a Ukrainian name often in Latin letters */
  private def listed(r: Random): String = weighted(r, 0 -> 35, 1 -> 15, 2 -> 20, 3 -> 15, 4 -> 15) match
    case 0 => person(r, Lang.pl, 0.7)
    case 1 => person(r, Lang.en, 0.7)
    case 2 => toLatin(person(r, Lang.uk, 0.7))
    case 3 => person(r, Lang.uk, 0.7)
    case _ => person(r, Lang.ru, 0.7)

  private val first = Reports.epochDay("2026-01-01")

  /** THE DESK OF A SEED */
  def desk(seed: Long): Desk =
    val r = new Random(seed * 7919 + 13)
    val today = first + r.nextInt(730)
    val days = (0 until weighted(r, 7 -> 1, 14 -> 3, 21 -> 2)).toVector.map(i => Reports.ofEpoch(today + i))
    val step = weighted(r, 15 -> 1, 30 -> 4, 60 -> 1)
    val times = ((7 + r.nextInt(4)) * 60 until (16 + r.nextInt(6)) * 60 by step).toVector
    val pool = if r.nextInt(4) == 0 then trades.take(3).flatten else pick(r, trades)
    val chosen = r.shuffle(pool).take(2 + r.nextInt(5)).toSet
    val services = pool.filter(chosen)
    val masters = r.shuffle(if r.nextInt(5) == 0 then cyrillicMasters else latinMasters)
      .take(weighted(r, 0 -> 2, 1 -> 3, 2 -> 7, 3 -> 5, 4 -> 3))
      .map((name, key) => key -> name)
    val crockford = r.nextBoolean()
    val prefix = pick(r, Vector("b", "B", "R", "K", "Z", "n", "v")) + (if r.nextBoolean() then "" else "-")
    val site = Vector.fill(2)(pick(r, ('A' to 'Z').toVector)).mkString + "B"
    val numbers = r.shuffle((1 to 60).toVector)
    val clients = Vector.fill(weighted(r, 0 -> 1, 2 -> 2, 4 -> 3, 6 -> 3, 8 -> 2))(listed(r)).distinct
    val bookings = clients.zipWithIndex.map { (client, i) =>
      val ref = if crockford then s"$site-${Ids.word(6, () => r.nextInt())}" else prefix + numbers(i)
      Booked(ref, client, pick(r, services)._1, if masters.isEmpty then "main" else pick(r, masters)._1,
        days(r.nextInt(math.min(days.size, 10))), pick(r, times))
    }.sortBy(b => (b.day, b.time))
    Desk(days, times, services, masters, bookings)

  /** the sealed set's shares of the actions, in fifteen */
  val shares: Vector[String] = Vector("book", "move", "paid", "book", "cancel", "book", "move", "dayoff", "book",
    "paid", "list", "move", "cancel", "book", "cannot")

  private def day(r: Random, d: Desk): String = weighted(r, 0 -> 3, 1 -> 4, 2 -> 13) match
    case 0 => d.days.head
    case 1 => d.days(1)
    case _ => pick(r, d.days)

  /**
   * A COMMAND of an action over a desk, its client named in a language. An action the desk has nothing for (no
   * booking to move, no master to free) turns into a booking or a list. A move changes the day, the time or both.
   */
  def command(r: Random, d: Desk, action: String, l: Lang): Command =
    def master = if d.masters.isEmpty || r.nextInt(100) < 35 then None else Some(pick(r, d.masters)._1)
    def book = Command.Book(person(r, l), pick(r, d.services)._1, master, day(r, d), pick(r, d.times))
    action match
      case "move" | "cancel" | "paid" if d.bookings.isEmpty => book
      case "move" =>
        val b = pick(r, d.bookings)
        def otherDay = pick(r, d.days.filter(_ != b.day))
        def otherTime = pick(r, d.times.filter(_ != b.time))
        weighted(r, 0 -> 35, 1 -> 30, 2 -> 35) match
          case 0 => Command.Move(b.ref, otherDay, otherTime)
          case 1 => Command.Move(b.ref, b.day, otherTime)
          case _ => Command.Move(b.ref, otherDay, b.time)
      case "cancel" => Command.Cancel(pick(r, d.bookings).ref)
      case "paid" =>
        val zl = weighted(r, (20 + 5 * r.nextInt(57)) -> 6, (20 + r.nextInt(480)) -> 3, (300 + 50 * r.nextInt(30)) -> 1)
        val grosze = if r.nextInt(12) == 0 then pick(r, Vector(50, 99, 20)) else 0
        Command.Paid(pick(r, d.bookings).ref, zl * 100L + grosze, weighted(r, Method.Cash -> 30,
          Method.Card -> 30, Method.Transfer -> 20, Method.Blik -> 15, Method.Other -> 5))
      case "dayoff" if d.masters.nonEmpty => Command.DayOff(pick(r, d.masters)._1, day(r, d))
      case "dayoff" | "list" => Command.Show(day(r, d), master)
      case "cannot" => Command.Cannot
      case _ => book

  /** what a master may ask that is no command of the shop's: the teacher's topics for `cannot` */
  val outside: Vector[String] = Vector("change the price of a service", "last month's revenue",
    "send a client a reminder message", "add a new service to the list", "the weather tomorrow",
    "give a client their money back", "add a new master to the team", "change the shop's opening hours",
    "order supplies (towels, paint, oil, strings)", "a greeting or small talk", "never book a certain client again",
    "make a discount code", "export the clients to a file", "change the password", "print an invoice",
    "how many clients a master had this year", "phone a client", "translate the shop's page",
    "what a client's phone number is", "write a post for social media", "a client's birthday greeting",
    "which service sells best", "raise a master's pay", "book a table at a restaurant", "a joke",
    "delete a client's review", "the salary due to a master", "close the shop for renovation next month",
    "recommend a shampoo or a product", "how to do some work of the trade")
