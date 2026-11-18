package okay.shop.commands

import okay.shop.commands.When.Part

/**
 * HOW A LANGUAGE SAYS DAYS AND TIMES, AS DATA (specs/commands.md A2b): the words `When` hears by. Every word is
 * written folded — lower case, `ё` as `е`, no apostrophe inside a word, Polish letters without their marks
 * (`środę` is `srode`: so is it typed on a phone) — as `When.fold` folds the phrase. A list of phrases is written
 * `a b|c`, the forms of one thing `a b c`.
 */
private[commands] final case class Tongue(
    name: String,
    /** Monday first: each day's forms and usual short writings */
    weekdays: Vector[Set[String]],
    months: Vector[Set[String]],
    /** `November 20` beside `20 November` */
    monthFirst: Boolean,
    /** today, tomorrow, the day after: the phrases and the days from today they mean */
    near: Vector[(Vector[Vector[String]], Vector[Int])],
    /** `in` N days, the units; a bare `через день` is tomorrow or the day after (`dayOrTwo`) */
    in: Set[String], dayUnit: Set[String], weekUnit: Set[String], dayOrTwo: Boolean,
    /** `this`, `next`, `the nearest` before a weekday or the week's word */
    thisOne: Vector[Vector[String]], nextOne: Vector[Vector[String]], week: Set[String],
    /** the words before a day; before a day or a time: from, until */
    on: Set[String], from: Set[String], until: Set[String], article: Set[String],
    /** what is written on to a date's digits (`20-го`, `20th`), the word after them (`20 числа`), a date's
     * ordinals in words */
    dateTail: Set[String], dateWord: Set[String], dates: Map[String, Int],
    /** numbers in words, in every case form */
    numbers: Map[String, Int],
    /** the words an hour is said by after `at`: cardinals or ordinals, as the language has it */
    hours: Map[String, Int],
    /** an hour's word that needs one of these words before it, or a part of the day after (`в час`) */
    soft: Set[String], softAt: Set[String],
    at: Set[String],
    /** `godz.` before an hour, `часов` or `o'clock` after it */
    hourBefore: Set[String], hourAfter: Set[String],
    /** what is written on to a time's digits (`15-ту`, `3pm`), with the part of the day it says */
    tails: Map[String, Option[Part]],
    /** `00` in words */
    zero: Vector[Vector[String]],
    /** the parts of a day: said after a time only (`дня`), and anywhere in the phrase (`вечером`) */
    partAfter: Vector[(Vector[Vector[String]], Part)], partAnywhere: Vector[(Vector[Vector[String]], Part)],
    /** `tonight`: a part of the day that is today's */
    todays: Vector[Vector[String]],
    noon: Vector[Vector[String]], midnight: Vector[Vector[String]],
    /** a move by a difference */
    later: Set[String], earlier: Set[String], by: Set[String], hourUnit: Set[String], minuteUnit: Set[String],
    halfHour: Vector[Vector[String]], hourAndHalf: Vector[Vector[String]],
    sameTime: Vector[Vector[String]], sameDay: Vector[Vector[String]],
    /** a number that is no time: after a word of paying, before a currency, people, a street */
    pay: Set[String], currency: Set[String], counted: Set[String],
    /** the language's own halves and quarters: at a place, the hour (1–12), the minute and the place after */
    idiom: (When.Hearing, Int, When.Role) => Option[(Int, Int, Int)],
    /** `a`, `an`: one, where a count is asked for */
    one: Set[String] = Set.empty)

private[commands] object Tongues:
  private def set(s: String): Set[String] = s.split(' ').filter(_.nonEmpty).toSet
  private def phrases(s: String): Vector[Vector[String]] = s.split('|').toVector.map(_.trim.split(' ').toVector)
  /** `a b|c` — the forms of 1, of 2, … */
  private def counted(s: String, from: Int = 1): Map[String, Int] =
    s.split('|').zipWithIndex.flatMap((forms, i) => set(forms).map(_ -> (i + from))).toMap
  private def each(s: String): Vector[Set[String]] = s.split('|').toVector.map(set)

  private val money = set("zl zlotych zlote zloty zlotego pln зл злотых злотий злотих злотые злотого грн гривен " +
    "гривень руб рублей eur euro евро євро usd € $ zlotys quid pounds")

  /** an hour before the one named: `half to six` is 5:30 */
  private def before(hour: Int): Int = if hour == 1 then 12 else hour - 1

  // ---- Russian ----
  private val ruNumbers = counted("один одна одну одного одной одному|два две двух двум|три трех трем|" +
    "четыре четырех четырем|пять пяти|шесть шести|семь семи|восемь восьми|девять девяти|десять десяти|" +
    "одиннадцать одиннадцати|двенадцать двенадцати|тринадцать тринадцати|четырнадцать четырнадцати|" +
    "пятнадцать пятнадцати|шестнадцать шестнадцати|семнадцать семнадцати|восемнадцать восемнадцати|" +
    "девятнадцать девятнадцати|двадцать двадцати") ++
    Map("тридцать" -> 30, "тридцати" -> 30, "сорок" -> 40, "сорока" -> 40, "пятьдесят" -> 50, "пару" -> 2)
  private val ruStems = Vector("перв", "втор", "трет", "четверт", "пят", "шест", "седьм", "восьм", "девят",
    "десят", "одиннадцат", "двенадцат", "тринадцат", "четырнадцат", "пятнадцат", "шестнадцат", "семнадцат",
    "восемнадцат", "девятнадцат", "двадцат")
  /** `шестого`: the hour a half or a quarter is of, and a date */
  private val ruOf: Map[String, Int] = ruStems.zipWithIndex.map((s, i) =>
    (if s == "трет" then "третьего" else s + "ого") -> (i + 1)).toMap + ("тридцатого" -> 30)
  private val ruDates = ruOf ++ ruStems.zipWithIndex.map((s, i) =>
    (if s == "трет" then "третье" else s + "ое") -> (i + 1)) + ("тридцатое" -> 30)

  private def ruIdiom(h: When.Hearing, i: Int, role: When.Role): Option[(Int, Int, Int)] =
    def of(j: Int) = h.word(j).flatMap(ruOf.get).filter(_ <= 12)
    h.word(i).flatMap {
      case w if w.startsWith("пол") && w.length > 3 && ruOf.get(w.drop(3)).exists(_ <= 12) =>
        Some((before(ruOf(w.drop(3))), 30, i + 1))
      case "пол" | "половина" | "половине" | "половину" => of(i + 1).map(o => (before(o), 30, i + 2))
      case "четверть" => of(i + 1).map(o => (before(o), 15, i + 2))
      case "без" =>
        val less = if h.word(i + 1).contains("четверти") then Some(15 -> (i + 2)) else h.count(i + 1)
        for (m, j) <- less if m > 0 && m < 30; (hour, n) <- h.clock(j) yield (before(hour), 60 - m, n)
      case _ =>
        // `десять минут шестого`, `три с половиной`
        (for (m, j) <- h.count(i) if m > 0 && m < 60 && h.is(j, Set("минут", "минуты")); o <- of(j + 1)
          yield (before(o), m, j + 2)).orElse(
          for (hour, j) <- h.clock(i) if h.word(j).contains("с") && h.word(j + 1).contains("половиной")
            yield (hour, 30, j + 2))
    }

  val ru: Tongue = Tongue("ru",
    weekdays = each("понедельник понедельника понедельнику понедельником понедельнике пн пон|" +
      "вторник вторника вторнику вторником вторнике вт|среда среду среды среде средой ср|" +
      "четверг четверга четвергу четвергом четверге чт|пятница пятницу пятницы пятнице пятницей пт|" +
      "суббота субботу субботы субботе субботой сб|" +
      "воскресенье воскресенья воскресенью воскресеньем воскресение вс вск"),
    months = each("январь января январе янв|февраль февраля феврале фев|март марта марте|" +
      "апрель апреля апреле апр|май мая мае|июнь июня июне|июль июля июле|август августа августе авг|" +
      "сентябрь сентября сентябре сен сент|октябрь октября октябре окт|ноябрь ноября ноябре ноя|" +
      "декабрь декабря декабре дек"),
    monthFirst = false,
    near = Vector(phrases("сегодня|сегодняшний|сегодняшние|сегодняшних|сегодняшнюю|сегодняшнего") -> Vector(0),
      phrases("завтра|завтрашний|завтрашние|завтрашних|завтрашнюю|завтрашнего") -> Vector(1),
      phrases("послезавтра|после завтра") -> Vector(2)),
    in = set("через"), dayUnit = set("день дня дней"), weekUnit = set("неделю недели недель"), dayOrTwo = true,
    thisOne = phrases("этот|эту|это|этого|этой|этом|ближайший|ближайшую|ближайшее|ближайшего|ближайшей"),
    nextOne = phrases("следующий|следующую|следующее|следующего|следующей|следующем"),
    week = set("неделя неделе недели неделю"),
    on = set("в во на к ко"), from = set("с со от"), until = set("до"), article = Set.empty,
    dateTail = set("го ого е ое"), dateWord = set("числа число"), dates = ruDates,
    numbers = ruNumbers,
    hours = ruNumbers.filter((w, n) => n <= 20 && w != "пару") ++ Map("час" -> 1, "часу" -> 1, "часа" -> 1),
    soft = set("час часу часа"), softAt = set("в к около с до"),
    at = set("в во на к ко около"), hourBefore = Set.empty, hourAfter = set("часов часа час ч"),
    tails = Map("ч" -> None, "ти" -> None, "х" -> None, "ть" -> None),
    zero = phrases("ноль ноль|ровно"),
    partAfter = Vector(phrases("утра") -> Part.Morning, phrases("дня") -> Part.Day,
      phrases("вечера") -> Part.Evening, phrases("ночи") -> Part.Night),
    partAnywhere = Vector(phrases("утром|с утра|утречком") -> Part.Morning,
      phrases("днем|после обеда|после полудня") -> Part.Day, phrases("вечером|вечерком") -> Part.Evening,
      phrases("ночью") -> Part.Night),
    todays = Vector.empty, noon = phrases("полдень"), midnight = phrases("полночь"),
    later = set("позже попозже позднее"), earlier = set("раньше пораньше ранее"), by = set("на"),
    hourUnit = set("час часа часов часик"), minuteUnit = set("минут минуты минуту мин"),
    halfHour = phrases("полчаса|пол часа|полчасика"), hourAndHalf = phrases("полтора часа"),
    sameTime = phrases("то же время|то же самое время|тоже время|это же время|время то же|время прежнее"),
    sameDay = phrases("тот же день|тот же самый день|этот же день|день тот же|того же дня"),
    pay = set("заплатил заплатила заплатили оплатил оплатила оплатили оплата оплачено взял взяла внес внесла " +
      "стоит цена отдал отдала"),
    currency = money, counted = set("человек человека людей персон персоны гостей"), idiom = ruIdiom)

  // ---- Ukrainian ----
  private val ukNumbers = counted("один одна одну одного однієї|два дві двох двом|три трьох трьом|" +
    "чотири чотирьох|пять пяти|шість шести|сім семи|вісім восьми|девять девяти|десять десяти|" +
    "одинадцять одинадцяти|дванадцять дванадцяти|тринадцять|чотирнадцять|пятнадцять пятнадцяти|" +
    "шістнадцять|сімнадцять|вісімнадцять|девятнадцять|двадцять двадцяти") ++
    Map("тридцять" -> 30, "тридцяти" -> 30, "тридцятій" -> 30, "сорок" -> 40, "пятдесят" -> 50, "пару" -> 2)
  private val ukStems = Vector("перш", "друг", "трет", "четверт", "пят", "шост", "сьом", "восьм", "девят",
    "десят", "одинадцят", "дванадцят", "тринадцят", "чотирнадцят", "пятнадцят", "шістнадцят", "сімнадцят",
    "вісімнадцят", "девятнадцят", "двадцят")
  /** an hour is an ordinal: `третя`, `третю`, `третьої`, `третій` */
  private val ukHours: Map[String, Int] = ukStems.zipWithIndex.flatMap { (s, i) =>
    (if s == "трет" then Vector("третя", "третю", "третьої", "третій")
      else Vector(s + "а", s + "у", s + "ої", s + "ій")).map(_ -> (i + 1))
  }.toMap
  private val ukDates: Map[String, Int] = ukStems.zipWithIndex.flatMap { (s, i) =>
    (if s == "трет" then Vector("третього", "третє") else Vector(s + "ого", s + "е")).map(_ -> (i + 1))
  }.toMap ++ Map("тридцятого" -> 30, "тридцяте" -> 30)

  private def ukIdiom(h: When.Hearing, i: Int, role: When.Role): Option[(Int, Int, Int)] =
    def hour(j: Int) = h.clock(j)
    def quarter(j: Int) = h.word(j).exists(Set("чверть", "чверті"))
    h.word(i).flatMap {
      case w if w.startsWith("пів") && w.length > 3 && ukHours.get(w.drop(3)).exists(_ <= 12) =>
        Some((before(ukHours(w.drop(3))), 30, i + 1))
      case "пів" | "половина" | "половині" | "половину" =>
        val j = if h.is(i + 1, Set("на", "до")) then i + 2 else i + 1
        hour(j).map((o, n) => (before(o), 30, n))
      case "чверть" => h.word(i + 1) match
        case Some("на") => hour(i + 2).map((o, n) => (before(o), 15, n))
        case Some("по") => hour(i + 2).map((o, n) => (o, 15, n))
        case Some("до") => hour(i + 2).map((o, n) => (before(o), 45, n))
        case _          => None
      case "за" | "без" =>
        val less = if quarter(i + 1) then Some(15 -> (i + 2)) else h.count(i + 1)
        for (m, j) <- less if m > 0 && m < 30; (o, n) <- hour(j) yield (before(o), 60 - m, n)
      case _ =>
        // `десять по пʼятій`, `десять на шосту`, `третя з половиною`
        (for
          (m, j0) <- h.count(i) if m > 0 && m < 60 && m % 5 == 0
          j = if h.is(j0, Set("хвилин", "хвилини")) then j0 + 1 else j0
          past <- h.word(j).collect { case "по" => true; case "на" => false }
          (o, n) <- hour(j + 1) if h.word(j + 1).exists(ukHours.contains)
        yield (if past then o else before(o), m, n)).orElse(
          for (o, j) <- hour(i) if h.word(j).contains("з") && h.word(j + 1).contains("половиною")
            yield (o, 30, j + 2))
    }

  val uk: Tongue = Tongue("uk",
    weekdays = each("понеділок понеділка понеділку понеділком пн пон|вівторок вівторка вівторку вівторком вт|" +
      "середа середу середи середі середою ср|четвер четверга четвергу четвергом чт|" +
      "пятниця пятницю пятниці пятницею пт|субота суботу суботи суботі суботою сб|" +
      "неділя неділю неділі неділею нд"),
    months = each("січень січня січні|лютий лютого лютому|березень березня березні|квітень квітня квітні|" +
      "травень травня травні|червень червня червні|липень липня липні|серпень серпня серпні|" +
      "вересень вересня вересні|жовтень жовтня жовтні|листопад листопада листопаді|грудень грудня грудні"),
    monthFirst = false,
    near = Vector(phrases("сьогодні|нині|сьогоднішній|сьогоднішні|сьогоднішніх|сьогоднішню") -> Vector(0),
      phrases("завтра|завтрашній|завтрашні|завтрашніх|завтрашню") -> Vector(1),
      phrases("післязавтра|після завтра|позавтра|післязавтрього") -> Vector(2)),
    in = set("через за"), dayUnit = set("день дні днів"), weekUnit = set("тиждень тижні тижнів"),
    dayOrTwo = true,
    thisOne = phrases("цей|цю|це|цього|цієї|цьому|цій|найближчий|найближчу|найближче|найближчого|найближчої"),
    nextOne = phrases("наступний|наступну|наступне|наступного|наступної|наступному|наступній"),
    week = set("тиждень тижня тижні тижню"),
    on = set("в у на"), from = set("з із зі від"), until = set("до"), article = Set.empty,
    dateTail = set("го ого е ше ге тє те ме"), dateWord = set("числа число"), dates = ukDates,
    numbers = ukNumbers, hours = ukHours ++ ukNumbers.filter((w, n) => n <= 20 && w != "пару"),
    soft = Set.empty, softAt = Set.empty,
    at = set("о об на в у близько біля"), hourBefore = Set.empty, hourAfter = set("година годину годині год"),
    tails = Map("ту" -> None, "у" -> None, "шу" -> None, "му" -> None, "й" -> None, "ій" -> None, "тій" -> None,
      "та" -> None, "а" -> None, "ої" -> None),
    zero = phrases("нуль нуль|рівно"),
    partAfter = Vector(phrases("ранку") -> Part.Morning, phrases("дня") -> Part.Day,
      phrases("вечора") -> Part.Evening, phrases("ночі") -> Part.Night),
    partAnywhere = Vector(phrases("вранці|уранці|зранку|зрання") -> Part.Morning,
      phrases("вдень|удень|після обіду|по обіді|пообіді") -> Part.Day,
      phrases("ввечері|увечері|увечорі|звечора") -> Part.Evening, phrases("вночі|уночі") -> Part.Night),
    todays = Vector.empty, noon = phrases("опівдні|полудень|полудні"), midnight = phrases("опівночі|північ"),
    later = set("пізніше"), earlier = set("раніше"), by = set("на"),
    hourUnit = set("годину години годин"), minuteUnit = set("хвилин хвилини хвилину хв"),
    halfHour = phrases("пів години|півгодини|півгодинки"), hourAndHalf = phrases("півтори години"),
    sameTime = phrases("той самий час|той же час|той же самий час|цей самий час|цей же час|ту саму годину|" +
      "ту ж годину|тій самій годині|тієї ж години|час той самий|час той же"),
    sameDay = phrases("той самий день|той же день|того ж дня|того самого дня|цей же день|цей самий день|" +
      "день той самий|день той же"),
    pay = set("заплатив заплатила заплатили сплатив сплатила сплатили сплачено оплатив оплатила оплата взяв " +
      "взяла коштує ціна"),
    currency = money, counted = set("осіб особи людей гостей години годин"), idiom = ukIdiom)

  // ---- Polish ----
  private val plNumbers = counted("jeden jedna jedno jednego|dwa dwie dwoch dwu|trzy trzech|cztery czterech|" +
    "piec pieciu|szesc szesciu|siedem siedmiu|osiem osmiu|dziewiec dziewieciu|dziesiec dziesieciu|jedenascie|" +
    "dwanascie|trzynascie|czternascie|pietnascie pietnastu|szesnascie|siedemnascie|osiemnascie|dziewietnascie|" +
    "dwadziescia dwudziestu") ++ Map("trzydziesci" -> 30, "trzydziestu" -> 30, "trzydziescie" -> 30,
    "czterdziesci" -> 40, "piecdziesiat" -> 50)
  private val plStems = Vector("pierwsz", "drug", "trzec", "czwart", "piat", "szost", "siodm", "osm", "dziewiat",
    "dziesiat", "jedenast", "dwunast", "trzynast", "czternast", "pietnast", "szesnast", "siedemnast",
    "osiemnast", "dziewietnast", "dwudziest")
  /** an hour is an ordinal: `trzecia`, `trzecią`, `trzeciej` */
  private val plHours: Map[String, Int] = plStems.zipWithIndex.flatMap { (s, i) =>
    (s match
      case "drug"  => Vector("druga", "drugiej")
      case "trzec" => Vector("trzecia", "trzeciej")
      case _       => Vector(s + "a", s + "ej")).map(_ -> (i + 1))
  }.toMap + ("jednej" -> 1)
  private val plDates: Map[String, Int] = plStems.zipWithIndex.flatMap { (s, i) =>
    (s match
      case "drug"  => Vector("drugiego")
      case "trzec" => Vector("trzeciego")
      case _       => Vector(s + "ego")).map(_ -> (i + 1))
  }.toMap + ("trzydziestego" -> 30)

  private def plIdiom(h: When.Hearing, i: Int, role: When.Role): Option[(Int, Int, Int)] =
    def hour(j: Int) = h.clock(j)
    h.word(i).flatMap {
      case "wpol" | "pol" if h.word(i + 1).contains("do") => hour(i + 2).map((o, n) => (before(o), 30, n))
      case "w" if h.word(i + 1).contains("pol") && h.word(i + 2).contains("do") =>
        hour(i + 3).map((o, n) => (before(o), 30, n))
      case "kwadrans" => h.word(i + 1) match
        case Some("po")             => hour(i + 2).map((o, n) => (o, 15, n))
        case Some("na")             => hour(i + 2).map((o, n) => (before(o), 15, n))
        case Some("przed" | "do")   => hour(i + 2).map((o, n) => (before(o), 45, n))
        case _                      => None
      case "za" =>
        val less = if h.word(i + 1).contains("kwadrans") then Some(15 -> (i + 2)) else h.count(i + 1)
        for (m, j) <- less if m > 0 && m < 30; (o, n) <- hour(j) if h.word(j).exists(plHours.contains)
          yield (before(o), 60 - m, n)
      case _ =>
        // `dziesięć po ósmej`, `dziesięć przed ósmą`
        for
          (m, j0) <- h.count(i) if m > 0 && m < 60 && m % 5 == 0
          j = if h.is(j0, Set("minut", "minuty")) then j0 + 1 else j0
          past <- h.word(j).collect { case "po" => true; case "przed" => false }
          (o, n) <- hour(j + 1) if h.word(j + 1).exists(plHours.contains)
        yield if past then (o, m, n) else (before(o), 60 - m, n)
    }

  val pl: Tongue = Tongue("pl",
    weekdays = each("poniedzialek poniedzialku poniedzialkiem pon pn|wtorek wtorku wtorkiem wt|" +
      "sroda srode srody srodzie sr|czwartek czwartku czwartkiem czw|piatek piatku piatkiem pt|" +
      "sobota sobote soboty sobocie sob sb|niedziela niedziele niedzieli nd niedz"),
    // no short writings: `sie` is also `się`, `lis` a surname, `mar` and `maj` names
    months = each("styczen stycznia styczniu|luty lutego lutym|marzec marca marcu|" +
      "kwiecien kwietnia kwietniu|maj maja maju|czerwiec czerwca czerwcu|lipiec lipca lipcu|" +
      "sierpien sierpnia sierpniu|wrzesien wrzesnia wrzesniu|" +
      "pazdziernik pazdziernika pazdzierniku|listopad listopada listopadzie|grudzien grudnia grudniu"),
    monthFirst = false,
    near = Vector(
      phrases("dzis|dzisiaj|dzisiejszy|dzisiejsza|dzisiejsze|dzisiejszych|dzisiejszego|dzisiejszej") -> Vector(0),
      phrases("jutro|jutra|jutrzejszy|jutrzejsza|jutrzejsze|jutrzejszych|jutrzejszego|jutrzejszej") -> Vector(1),
      phrases("pojutrze|po jutrze") -> Vector(2)),
    in = set("za"), dayUnit = set("dzien dni"), weekUnit = set("tydzien tygodnie tygodni"), dayOrTwo = false,
    thisOne = phrases("ten|te|ta|to|tego|tej|tym|najblizszy|najblizsza|najblizsze|najblizszego|najblizszej"),
    nextOne = phrases("przyszly|przyszla|przyszle|przyszlego|przyszlej|przyszlym|nastepny|nastepna|nastepne|" +
      "nastepnego|nastepnej|nastepnym"),
    week = set("tydzien tygodnia tygodniu"),
    on = set("w we na"), from = set("od z ze"), until = set("do"), article = set("dnia"),
    dateTail = set("go ego tego"), dateWord = Set.empty, dates = plDates,
    numbers = plNumbers, hours = plHours, soft = Set.empty, softAt = Set.empty,
    at = set("o na kolo okolo"), hourBefore = set("godz godzina godzine godzinie"), hourAfter = Set.empty,
    tails = Map("ta" -> None, "tej" -> None, "ej" -> None, "a" -> None, "sta" -> None, "stej" -> None),
    zero = phrases("zero zero|rowno"),
    partAfter = Vector.empty,
    partAnywhere = Vector(phrases("rano|z rana|przed poludniem") -> Part.Morning,
      phrases("po poludniu|popoludniu") -> Part.Day, phrases("wieczorem|wieczor|wieczora") -> Part.Evening,
      phrases("w nocy") -> Part.Night),
    todays = Vector.empty, noon = phrases("poludnie"), midnight = phrases("polnoc|polnocy"),
    later = set("pozniej"), earlier = set("wczesniej"), by = set("o"),
    hourUnit = set("godzine godziny godzin godzina"), minuteUnit = set("minut minuty minute min"),
    halfHour = phrases("pol godziny|polgodziny|pol godzinki"), hourAndHalf = phrases("poltorej godziny"),
    sameTime = phrases("tej samej porze|ta sama pora|te sama pore|tej samej godzinie|ta sama godzina|" +
      "te sama godzine|tej samej godziny|ten sam czas|tym samym czasie|godzina ta sama|godzina bez zmian"),
    sameDay = phrases("tego samego dnia|ten sam dzien|tym samym dniu|dzien ten sam|dzien bez zmian"),
    pay = set("zaplacil zaplacila zaplacili zaplacone zaplacono placi wplacil wplacila wzialem wzielam " +
      "kosztuje cena oplacone"),
    currency = money, counted = set("osob osoby ludzi gosci godziny godzin"), idiom = plIdiom)

  // ---- English ----
  private val enHours = counted("one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve")
  private val enNumbers = enHours ++ counted("thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|" +
    "twenty", 13) ++ Map("thirty" -> 30, "forty" -> 40, "fifty" -> 50, "couple" -> 2)
  private val enDates = counted("first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|" +
    "twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth") +
    ("thirtieth" -> 30)

  private def enIdiom(h: When.Hearing, i: Int, role: When.Role): Option[(Int, Int, Int)] =
    def hour(j: Int) = h.clock(j).filter(_._1 <= 12)
    def after(j: Int) = h.is(j, Set("past", "after"))
    def to(j: Int) = h.is(j, Set("to", "till", "of", "before"))
    h.word(i).flatMap {
      case "half" if h.word(i + 1).contains("past") => hour(i + 2).map((o, n) => (o, 30, n))
      case "half" if h.word(i + 1).exists(enHours.contains) => hour(i + 1).map((o, n) => (o, 30, n))
      case "a" if h.word(i + 1).contains("quarter") => enIdiom(h, i + 1, role)
      case "quarter" if after(i + 1) => hour(i + 2).map((o, n) => (o, 15, n))
      case "quarter" if to(i + 1) => hour(i + 2).map((o, n) => (before(o), 45, n))
      case _ if role == When.Role.From => None // `from ten to four` is two times
      case w if w != "a" && w != "an" =>
        for
          (m, j0) <- h.count(i) if m > 0 && m < 60 && m % 5 == 0
          j = if h.is(j0, Set("minutes", "mins")) then j0 + 1 else j0
          past <- if after(j) then Some(true) else if to(j) && m < 30 then Some(false) else None
          (o, n) <- hour(j + 1)
        yield if past then (o, m, n) else (before(o), 60 - m, n)
      case _ => None
    }

  val en: Tongue = Tongue("en",
    weekdays = each("monday mon|tuesday tue tues|wednesday wed weds|thursday thu thur thurs|friday fri|" +
      "saturday sat|sunday sun"),
    months = each("january jan|february feb|march mar|april apr|may|june jun|july jul|august aug|" +
      "september sep sept|october oct|november nov|december dec"),
    monthFirst = true,
    near = Vector(phrases("today|todays") -> Vector(0), phrases("tomorrow|tomorrows|tmrw|tmr") -> Vector(1),
      phrases("the day after tomorrow|day after tomorrow") -> Vector(2)),
    in = set("in"), dayUnit = set("day days"), weekUnit = set("week weeks"), dayOrTwo = false,
    thisOne = phrases("this coming|this|coming"), nextOne = phrases("next|the following|following"),
    week = set("week"),
    on = set("on for to"), from = set("from"), until = set("until till til to"), article = set("the of"),
    dateTail = set("st nd rd th"), dateWord = Set.empty, dates = enDates,
    numbers = enNumbers, hours = enHours, soft = Set.empty, softAt = Set.empty,
    at = set("at to around"), hourBefore = Set.empty, hourAfter = set("oclock"),
    tails = Map("am" -> Some(Part.Am), "pm" -> Some(Part.Pm), "a" -> Some(Part.Am), "p" -> Some(Part.Pm)),
    zero = Vector.empty,
    partAfter = Vector(phrases("am") -> Part.Am, phrases("pm") -> Part.Pm, phrases("at night") -> Part.Night),
    partAnywhere = Vector(phrases("in the morning|this morning|morning") -> Part.Morning,
      phrases("in the afternoon|this afternoon|afternoon") -> Part.Day,
      phrases("in the evening|this evening|evening|tonight") -> Part.Evening),
    todays = phrases("tonight|this evening|this afternoon|this morning"),
    noon = phrases("noon|midday"), midnight = phrases("midnight"),
    later = set("later"), earlier = set("earlier sooner"), by = set("by"),
    hourUnit = set("hour hours hr hrs"), minuteUnit = set("minutes minute min mins"),
    halfHour = phrases("half an hour|half hour|half a hour"),
    hourAndHalf = phrases("an hour and a half|one and a half hours|one hour and a half"),
    sameTime = phrases("the same time|same time|same hour|the same hour"),
    sameDay = phrases("the same day|same day"),
    pay = set("paid pays pay payment charged charge cost costs price took"),
    currency = money,
    counted = set("people persons person guests kids hours hour hrs street st road rd avenue ave lane"),
    idiom = enIdiom, one = set("a an"))

  /** every language's words of paying, and of what a number counts that is no hour: a phrase is heard by each
   * language of its letters, and one must not take for a time what another knows for people or money */
  lazy val paying: Set[String] = Vector(ru, uk, pl, en).flatMap(_.pay).toSet
  lazy val counting: Set[String] =
    Vector(ru, uk, pl, en).flatMap(t => t.counted ++ t.minuteUnit ++ t.dayUnit ++ t.weekUnit).toSet ++ money

  /** the languages a phrase may be written in: those of its letters */
  def of(text: String): Vector[Tongue] =
    val cyrillic = text.exists(c => c >= 'Ѐ' && c <= 'ӿ')
    val latin = text.exists(c => c.isLetter && c < 'ɐ')
    (if cyrillic then Vector(ru, uk) else Vector.empty) ++ (if latin then Vector(pl, en) else Vector.empty)
