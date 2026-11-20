package okay.shop

import okay.frames.{Laws, Reading}
import okay.shop.commands.*
import okay.shop.model.*
import scala.util.Random

/**
 * DAYS AND TIMES COMPUTED (specs/commands.md A2b): `When` on a table of expressions a language, written from each
 * language's own way of saying days and times — and of what is neither (an amount, a reference, a street, people) —
 * and `Computed`, the space of commands whose day and time lines are narrowed by it. No phrase here is of the
 * sealed set.
 *
 * The desk: today is Wednesday 2027-03-03, fourteen days (Thu 1, Fri 2, Sat 3, Sun 4, Mon 5, Tue 6, Wed 7, …,
 * Tue 13), starts every quarter from 07:00 to 21:45 — so `at 8` is two starts and `at 3` is one. A row is
 * `phrase | days | times`: days by their place in the horizon; `-` nothing said, `x` said and not the desk's.
 */
class WhenSuite extends munit.FunSuite:
  private val services = Vector("haircut" -> T("Стрижка", "Strzyżenie", "Haircut", "Стрижка"),
    "kids" -> T("Детская стрижка", "Strzyżenie dziecięce", "Kids' haircut", "Дитяча стрижка"),
    "documents" -> T("Фото на документы", "Zdjęcia do dokumentów", "ID photos", "Фото на документи"),
    "polish" -> T("Польский", "Polski", "Polish", "Польська"))
  private val desk = Desk(
    days = (0 until 14).toVector.map(i => Reports.plus("2027-03-03", i)),
    times = (420 until 1320 by 15).toVector, services = services,
    masters = Vector("olena" -> "Olena", "marek" -> "Marek"),
    bookings = Vector(Booked("b1", "Anna Kowalska", "haircut", "olena", "2027-03-05", 600)))

  private def shown(n: Narrowed, of: String => String): String = n match
    case Narrowed.Unsaid         => "-"
    case Narrowed.Outside(_)     => "x"
    case Narrowed.Among(options) => options.map(of).mkString(",")

  private def check(rows: String, action: String, booking: Option[Booked]): Int =
    val table = rows.linesIterator.map(_.trim).filter(_.nonEmpty).map(_.split('|').map(_.trim)).toVector
    for row <- table do
      val Array(phrase, days, times) = row
      assertEquals(shown(When.days(desk, phrase, action, booking), d => desk.days.indexOf(d).toString), days,
        s"the days of: $phrase — heard ${When.hear(phrase)}")
      assertEquals(shown(When.times(desk, phrase, action, booking), identity), times,
        s"the times of: $phrase — heard ${When.hear(phrase)}")
    table.size

  private val nextWeek = "5,6,7,8,9,10,11"

  test("English: days and times as they are said, and what is neither"):
    val n = check(s"""
      Book Emma for a haircut today at 3 | 0 | 15:00
      Put Tom down tomorrow at 10 | 1 | 10:00
      Sophie, the day after tomorrow, 9:30 | 2 | 09:30,21:30
      Jack on Friday at 15:00 | 2 | 15:00
      Book Lucy on Wednesday at noon | 0,7 | 12:00
      Grace this Wednesday at 4pm | 0 | 16:00
      Hannah next Friday at 2 pm | 2,9 | 14:00
      Ben next Monday, 11 am | 5 | 11:00
      Kate on Tuesday next week at 8 | 6 | 08:00,20:00
      next week Friday for Megan, 5 in the evening | 9 | 17:00
      Ruth in 3 days at half past five | 3 | 17:30
      Zoe in a week at quarter to ten | 7 | 09:45,21:45
      Alice in two weeks at 9 | x | 09:00,21:00
      Harry on 5 March at 3 o'clock | 2 | 15:00
      Sam on March 5th at 3:15 pm | 2 | 15:15
      Luke on the 15th at 10 in the morning | 12 | 10:00
      Oliver on 05.03 at 16:30 | 2 | 16:30
      George 10.03 at 09:00 | 7 | 09:00
      Dan 10/03, 14:45 | 7 | 14:45
      Max on the twentieth | x | -
      Book Emma on Mon at 7 | 5 | 07:00,19:00
      Chloe Thu 6pm | 1 | 18:00
      Olivia tomorrow morning at 8 | 1 | 08:00
      Jess this afternoon at 2 | 0 | 14:00
      Tom tonight at 8 | 0 | 20:00
      Tom at eight in the evening on Saturday | 3 | 20:00
      Emma Saturday at ten thirty | 3 | 10:30
      Emma on Sunday at a quarter past four | 4 | 16:15
      Emma on Sunday at ten past four | 4 | x
      Emma at half three on Friday | 2 | 15:30
      Emma Friday at twelve | 2 | 12:00
      Emma at 12:30pm tomorrow | 1 | 12:30
      Emma at 19 tomorrow | 1 | 19:00
      Emma tomorrow at 07:30 | 1 | 07:30
      Emma tomorrow at 7.30 | 1 | 07:30,19:30
      Emma tomorrow 3pm | 1 | 15:00
      Emma tomorrow at 3 p.m. | 1 | 15:00
      What have I got tomorrow? | 1 | -
      Who is coming on Friday? | 2 | -
      Show me next week | $nextWeek | -
      Marek is off on Saturday | 3 | -
      Anna paid 150 by card | - | -
      Ivan paid 15 in cash | - | -
      Piotr paid 12.50 | - | -
      Maria's booking b12 is paid, 80 zł | - | -
      Cancel booking R-17 | - | -
      The salon at 12 Baker Street is closed | - | -
      Book a table for 3 people | - | -
      Emma wants 2 haircuts | - | -
      Call me on 600 000 000 | - | -
      Book Emma for two thirty-minute massages | - | -
      Emma from 3 to 4 tomorrow | 1 | 15:00
      Emma from 10 until 11 on Friday | 2 | 10:00
      Emma on the 5th of March at 9 am | 2 | 09:00
      Emma, Friday 05.03 at 15:00 | 2 | 15:00
      Emma tomorrow, Thursday, at 5 | 1 | 17:00
      Emma at midday on Monday | 5 | 12:00
      Emma Mon 08.03 14:00 | 5 | 14:00
      Friday 9 | 2 | -
      See you later | - | -
      Emma around 4 on Tuesday | 6 | 16:00
      Emma on Friday at 9 at night | 2 | 21:00
      this coming Saturday at 11 | 3 | 11:00
      Emma 3 March at 10:15 | 0 | 10:15
      Emma at 22:00 tomorrow | 1 | x
      Emma at 3 am tomorrow | 1 | x
      Emma on Friday at a quarter to six in the evening | 2 | 17:45
      Emma tomorrow at fifteen thirty | 1 | 15:30
      Emma tomorrow at 5 past 3 | 1 | x
      Emma tomorrow at 15 past 3 | 1 | 15:15
      Kate on Saturday instead of Friday | 3 | -
      Show today's bookings | 0 | -
      Book a thirty minute slot | - | -
      """, "book", None)
    assert(n >= 60, n)

  test("Russian: days and times as they are said, and what is neither"):
    val n = check(s"""
      Запиши Олю на стрижку сегодня в 3 | 0 | 15:00
      Лену завтра на 10 | 1 | 10:00
      Таню послезавтра в 9:30 | 2 | 09:30,21:30
      Иру после завтра в 15:00 | 2 | 15:00
      Юлю в пятницу в три | 2 | 15:00
      Дашу в среду в полдень | 0,7 | 12:00
      Настю в эту среду в 4 вечера | 0 | 16:00
      Вику в следующую пятницу в 2 часа дня | 2,9 | 14:00
      Марину в следующий понедельник в 11 утра | 5 | 11:00
      Галину во вторник на следующей неделе в 8 | 6 | 08:00,20:00
      На следующей неделе в пятницу Веру, в пять вечера | 9 | 17:00
      Сашу через 3 дня на полшестого | 3 | 17:30
      Диму через неделю без четверти десять | 7 | 09:45,21:45
      Сергея через две недели в 9 | x | 09:00,21:00
      Мишу 5 марта в 3 часа | 2 | 15:00
      Костю пятого марта в три пятнадцать дня | 2 | 15:15
      Пашу 15-го в 10 утра | 12 | 10:00
      Олега 05.03 в 16:30 | 2 | 16:30
      Виктора 10.03 в 09:00 | 7 | 09:00
      Женю двадцатого | x | -
      Николая в пн в 7 | 5 | 07:00,19:00
      Артёма в чт в 18 | 1 | 18:00
      Наташу завтра утром в 8 | 1 | 08:00
      Лену сегодня после обеда в 2 | 0 | 14:00
      Таню сегодня вечером в 8 | 0 | 20:00
      Иру в субботу в восемь вечера | 3 | 20:00
      Юлю в субботу в десять тридцать | 3 | 10:30
      Дашу в воскресенье в четверть пятого | 4 | 16:15
      Настю в воскресенье в половине четвёртого | 4 | 15:30
      Вику в пятницу в пол четвертого | 2 | 15:30
      Марину в пятницу в двенадцать | 2 | 12:00
      Галину завтра в час | 1 | 13:00
      Веру завтра в час дня | 1 | 13:00
      Сашу завтра к часу | 1 | 13:00
      Диму завтра в 19 | 1 | 19:00
      Сергея завтра в 07:30 | 1 | 07:30
      Мишу завтра в 7.30 | 1 | 07:30,19:30
      Что у меня завтра? | 1 | -
      Кто записан на пятницу? | 2 | -
      Покажи следующую неделю | $nextWeek | -
      У Марека выходной в субботу | 3 | -
      Анна заплатила 150 картой | - | -
      Иван заплатил 15 наличными | - | -
      Пётр оплатил 12.50 | - | -
      Запись b12 оплачена, 80 зл | - | -
      Отмени запись R-17 | - | -
      Салон на Садовой 12 закрыт | - | -
      Столик на 3 человек | - | -
      Оле нужно 2 стрижки | - | -
      Позвони на 600 000 000 | - | -
      Перенеси Олю на час | - | -
      Олю с 3 до 4 завтра | 1 | 15:00
      Олю с 10 до 11 в пятницу | 2 | 10:00
      Олю 5-го марта в 9 утра | 2 | 09:00
      Олю, пятница 05.03 в 15:00 | 2 | 15:00
      Олю завтра, в четверг, в 5 | 1 | 17:00
      Олю в понедельник в полдень | 5 | 12:00
      Пятница 9 | 2 | -
      Увидимся позже | - | -
      Олю около четырёх во вторник | 6 | 16:00
      Олю в пятницу в 9 вечера | 2 | 21:00
      Олю в ближайшую субботу в 11 | 3 | 11:00
      Олю 3 марта в 10:15 | 0 | 10:15
      Олю завтра в 22:00 | 1 | x
      Олю в пятницу без пятнадцати шесть | 2 | 17:45
      Олю через день в 10 | 1,2 | 10:00
      Олю в три с половиной в пятницу | 2 | 15:30
      Олю завтра в семнадцать тридцать | 1 | 17:30
      Олю завтра в пятнадцать ноль ноль | 1 | 15:00
      Олю на массаж на 3 часа | - | 15:00
      Олю в пятницу двадцать минут шестого | 2 | x
      Олю завтра в 3 часа 30 минут | 1 | 15:30
      Покажи завтрашние записи | 1 | -
      Олю завтра в 15 минут шестого | 1 | 17:15
      Анну в субботу вместо пятницы | 3 | -
      """, "book", None)
    assert(n >= 60, n)

  test("Ukrainian: days and times as they are said, and what is neither"):
    val n = check(s"""
      Запиши Олю на стрижку сьогодні о 3 | 0 | 15:00
      Галю завтра на 10 | 1 | 10:00
      Іру післязавтра о 9:30 | 2 | 09:30,21:30
      Юлю позавтра о 15:00 | 2 | 15:00
      Лесю в п'ятницю о третій | 2 | 15:00
      Тетяну в середу опівдні | 0,7 | 12:00
      Марічку цієї середи о 4 вечора | 0 | 16:00
      Христину наступної п'ятниці о 2 дня | 2,9 | 14:00
      Надію в наступний понеділок об 11 ранку | 5 | 11:00
      Тараса у вівторок наступного тижня о 8 | 6 | 08:00,20:00
      На наступному тижні в п'ятницю Богдана, о п'ятій вечора | 9 | 17:00
      Сашка через 3 дні на пів на шосту | 3 | 17:30
      Назара за тиждень за чверть десята | 7 | 09:45,21:45
      Остапа через два тижні о 9 | x | 09:00,21:00
      Василя 5 березня о 3 годині | 2 | 15:00
      Миколу п'ятого березня о третій п'ятнадцять дня | 2 | 15:15
      Юрка 15-го о 10 ранку | 12 | 10:00
      Дмитра 05.03 о 16:30 | 2 | 16:30
      Павла 10.03 о 09:00 | 7 | 09:00
      Наталку двадцятого | x | -
      Галю в пн о 7 | 5 | 07:00,19:00
      Іру в чт на 18 | 1 | 18:00
      Юлю завтра зранку о 8 | 1 | 08:00
      Лесю сьогодні після обіду о 2 | 0 | 14:00
      Тетяну сьогодні ввечері о 8 | 0 | 20:00
      Марічку в суботу о восьмій вечора | 3 | 20:00
      Христину в суботу о десятій тридцять | 3 | 10:30
      Надію в неділю чверть на п'яту | 4 | 16:15
      Тараса в неділю о пів на четверту | 4 | 15:30
      Богдана в п'ятницю на пів четвертої | 2 | 15:30
      Сашка в п'ятницю о дванадцятій | 2 | 12:00
      Назара завтра на першу | 1 | 13:00
      Остапа завтра о першій дня | 1 | 13:00
      Василя завтра на дев'яту | 1 | 09:00,21:00
      Миколу завтра о 19 | 1 | 19:00
      Юрка завтра о 07:30 | 1 | 07:30
      Дмитра завтра на пів на першу | 1 | 12:30
      Що в мене завтра? | 1 | -
      Хто записаний на п'ятницю? | 2 | -
      Покажи наступний тиждень | $nextWeek | -
      У Марека вихідний у суботу | 3 | -
      Анна заплатила 150 карткою | - | -
      Іван заплатив 15 готівкою | - | -
      Петро сплатив 12.50 | - | -
      Запис b12 сплачено, 80 зл | - | -
      Скасуй запис R-17 | - | -
      Салон на Садовій 12 зачинено | - | -
      Столик на 3 особи | - | -
      Олі треба 2 стрижки | - | -
      Масаж на 2 години | - | -
      Олю з 3 до 4 завтра | 1 | 15:00
      Олю з 10 до 11 в п'ятницю | 2 | 10:00
      Олю 5-го березня о 9 ранку | 2 | 09:00
      Олю, п'ятниця 05.03 о 15:00 | 2 | 15:00
      Олю завтра, в четвер, о 5 | 1 | 17:00
      Олю в понеділок опівдні | 5 | 12:00
      П'ятниця 9 | 2 | -
      Побачимось пізніше | - | -
      Олю близько четвертої у вівторок | 6 | 16:00
      Олю в п'ятницю о 9 вечора | 2 | 21:00
      Олю найближчої суботи об 11 | 3 | 11:00
      Олю 3 березня о 10:15 | 0 | 10:15
      Олю завтра о 22:00 | 1 | x
      Олю в п'ятницю за п'ятнадцять шоста | 2 | 17:45
      Олю на 15-ту завтра | 1 | 15:00
      Олю завтра о сімнадцятій тридцять | 1 | 17:30
      Олю в п'ятницю чверть по п'ятій | 2 | 17:15
      Олю в п'ятницю о пів до шостої | 2 | 17:30
      Олю завтра о третій годині дня | 1 | 15:00
      Олю в п’ятницю на дев’яту ранку | 2 | 09:00
      Лесю в п'ятницю о пів на п'ятнадцяту | 2 | 14:30
      Іру в четвер о сьомій годині тридцять хвилин | 1 | 07:30,19:30
      Що на 10-те у всіх? | 7 | -
      Назара в понеділок о половині дванадцятої | 5 | 11:30
      Надію завтра о восьмій тридцятій | 1 | 08:30,20:30
      Олю завтра 15 по п'ятій | 1 | 17:15
      Анну в суботу замість п'ятниці | 3 | -
      """, "book", None)
    assert(n >= 60, n)

  test("Polish: days and times as they are said, and what is neither"):
    val n = check(s"""
      Zapisz Ewę na strzyżenie dzisiaj o 3 | 0 | 15:00
      Magdę jutro na 10 | 1 | 10:00
      Olę pojutrze o 9:30 | 2 | 09:30,21:30
      Zosię po jutrze o 15:00 | 2 | 15:00
      Basię w piątek o trzeciej | 2 | 15:00
      Martę w środę w południe | 0,7 | 12:00
      Iwonę w tę środę o 4 po południu | 0 | 16:00
      Dorotę w przyszły piątek o 14 | 2,9 | 14:00
      Justynę w przyszły poniedziałek o 11 rano | 5 | 11:00
      Monikę we wtorek w przyszłym tygodniu o 8 | 6 | 08:00,20:00
      W przyszłym tygodniu w piątek Gosię, o piątej wieczorem | 9 | 17:00
      Tomka za 3 dni na wpół do szóstej | 3 | 17:30
      Krzysztofa za tydzień za kwadrans dziesiąta | 7 | 09:45,21:45
      Pawła za dwa tygodnie o 9 | x | 09:00,21:00
      Michała 5 marca o godz. 15 | 2 | 15:00
      Jacka piątego marca o trzeciej piętnaście po południu | 2 | 15:15
      Darka 15-go o 10 rano | 12 | 10:00
      Bartka 05.03 o 16:30 | 2 | 16:30
      Łukasza 10.03 o 09:00 | 7 | 09:00
      Grzegorza dwudziestego | x | -
      Staszka w pon o 7 | 5 | 07:00,19:00
      Ewę w czw na 18 | 1 | 18:00
      Magdę jutro rano o 8 | 1 | 08:00
      Olę dzisiaj po południu o 2 | 0 | 14:00
      Zosię dziś wieczorem o 8 | 0 | 20:00
      Basię w sobotę o ósmej wieczorem | 3 | 20:00
      Martę w sobotę o dziesiątej trzydzieści | 3 | 10:30
      Iwonę w niedzielę kwadrans po czwartej | 4 | 16:15
      Dorotę w niedzielę o wpół do czwartej | 4 | 15:30
      Justynę w piątek na w pół do czwartej | 2 | 15:30
      Monikę w piątek o dwunastej | 2 | 12:00
      Gosię jutro na pierwszą | 1 | 13:00
      Tomka jutro o pierwszej po południu | 1 | 13:00
      Krzysztofa jutro na dziewiątą | 1 | 09:00,21:00
      Pawła jutro o 19 | 1 | 19:00
      Michała jutro o 07:30 | 1 | 07:30
      Jacka jutro o 7.30 | 1 | 07:30,19:30
      Co mam jutro? | 1 | -
      Kto jest zapisany na piątek? | 2 | -
      Pokaż przyszły tydzień | $nextWeek | -
      Marek ma wolne w sobotę | 3 | -
      Anna zapłaciła 150 kartą | - | -
      Iwan zapłacił 15 gotówką | - | -
      Piotr zapłacił 12.50 | - | -
      Rezerwacja b12 opłacona, 80 zł | - | -
      Odwołaj rezerwację R-17 | - | -
      Salon na Lipowej 12 jest zamknięty | - | -
      Stolik na 3 osoby | - | -
      Ewa chce 2 strzyżenia | - | -
      Masaż na 2 godziny | - | -
      Ewę od 3 do 4 jutro | 1 | 15:00
      Ewę od 10 do 11 w piątek | 2 | 10:00
      Ewę 5-go marca o 9 rano | 2 | 09:00
      Ewę, piątek 05.03, godz. 15:00 | 2 | 15:00
      Ewę jutro, w czwartek, o 5 | 1 | 17:00
      Ewę w poniedziałek w południe | 5 | 12:00
      Piątek 9 | 2 | -
      Do zobaczenia później | - | -
      Ewę około czwartej we wtorek | 6 | 16:00
      Ewę w piątek o 9 wieczorem | 2 | 21:00
      Ewę w najbliższą sobotę o 11 | 3 | 11:00
      Ewę 3 marca o 10:15 | 0 | 10:15
      Ewę jutro o 22:00 | 1 | x
      Ewę w piątek za piętnaście szósta | 2 | 17:45
      Ewę na 15-tą jutro | 1 | 15:00
      Ewę jutro o siedemnastej trzydzieści | 1 | 17:30
      Ewę w piątek kwadrans na szóstą | 2 | 17:15
      Ewe w srode o 15 | 0,7 | 15:00
      Ewę jutro o godzinie 15 | 1 | 15:00
      O 10 się nie da | - | 10:00
      Ewę jutro na wpół do piętnastej | 1 | 14:30
      Ewę jutro na piętnaście piętnaście | 1 | 15:15
      Lista dzisiejszych zapisów | 0 | -
      Olę dziś na wpół do jednej | 0 | 12:30
      Ewę jutro 15 po czwartej | 1 | 16:15
      Annę w sobotę zamiast piątku | 3 | -
      """, "book", None)
    assert(n >= 60, n)

  test("a move by a difference is computed from the booking, and a move goes from what it has"):
    val anna = desk.bookings.headOption // Friday (2) at 10:00
    val n = check("""
      Move Anna an hour later | - | 11:00
      Move Anna half an hour earlier | - | 09:30
      Anna 2 hours later | - | 12:00
      Push Anna to Monday, same time | 5 | 10:00
      Anna the same day at 4pm | 2 | 16:00
      Anna a day later | 3 | -
      Anna a week later, the same time | 9 | 10:00
      Move Anna from Friday to Saturday | 3 | -
      Move Anna from 10 to 12 | - | 12:00
      Move Anna's Friday haircut to 11:30 | 2 | 11:30
      Anna an hour and a half later | - | 11:30
      Anna 30 minutes later | - | 10:30
      Anna later by an hour | - | 11:00
      Move Anna to tomorrow at 10 | 1 | 10:00
      Anna 13 hours later | - | x
      Anna's Friday 10:00 haircut, make it Saturday 12:00 | 3 | 12:00
      Анна, пятница 10:00, пусть будет суббота 12:00 | 3 | 12:00
      Перенеси Анну на час позже | - | 11:00
      Анну на полчаса раньше | - | 09:30
      Анну на 2 часа позже | - | 12:00
      Анну на понедельник, в то же время | 5 | 10:00
      Анну в тот же день на 4 вечера | 2 | 16:00
      Анну на день позже | 3 | -
      Анну на неделю позже, время то же | 9 | 10:00
      Перенеси Анну с пятницы на субботу | 3 | -
      Перенеси Анну с 10 на 12 | - | 12:00
      Анну с пятницы на 11:30 | 2 | 11:30
      Анну попозже на полтора часа | - | 11:30
      Анну на 30 минут позже | - | 10:30
      Анну на завтра на 10 | 1 | 10:00
      Перенеси Анну на годину пізніше | - | 11:00
      Анну на пів години раніше | - | 09:30
      Анну на 2 години пізніше | - | 12:00
      Анну на понеділок, на той самий час | 5 | 10:00
      Анну того ж дня на 4 вечора | 2 | 16:00
      Анну на день пізніше | 3 | -
      Анну на тиждень пізніше, час той самий | 9 | 10:00
      Перенеси Анну з п'ятниці на суботу | 3 | -
      Перенеси Анну з 10 на 12 | - | 12:00
      Анну на півтори години пізніше | - | 11:30
      Анну на 30 хвилин пізніше | - | 10:30
      Przenieś Annę o godzinę później | - | 11:00
      Annę pół godziny wcześniej | - | 09:30
      Annę o 2 godziny później | - | 12:00
      Annę na poniedziałek, o tej samej porze | 5 | 10:00
      Annę tego samego dnia na 16 | 2 | 16:00
      Annę dzień później | 3 | -
      Annę tydzień później, ta sama godzina | 9 | 10:00
      Przenieś Annę z piątku na sobotę | 3 | -
      Przenieś Annę z 10 na 12 | - | 12:00
      Annę o półtorej godziny później | - | 11:30
      Annę o 30 minut później | - | 10:30
      Annę na jutro na 10 | 1 | 10:00
      """, "move", anna)
    assert(n >= 48, n)
    // a difference is of a move alone: a new booking has nothing to differ from
    assertEquals(When.times(desk, "Book Emma an hour later", "book"), Narrowed.Unsaid)
    assertEquals(When.days(desk, "Emma the same day", "book"), Narrowed.Unsaid)
    assertEquals(When.times(desk, "Move Anna an hour later", "move", None), Narrowed.Unsaid)

  test("a move that names the booking's own day or time and the new one (A2d)"):
    val anna = desk.bookings.headOption // Friday (2) at 10:00
    // old then new; new then old; the old time and a difference (`in half an hour` only beside the old time);
    // only the new one; the next day; a week from today or from the booking's day; `next Friday` beside Friday
    // (A2f: the Friday a week after the booking, 9 — it was `2,9`)
    val near = check("""
      Anna can't make Friday at 10, she'll come Monday at 12 | 5 | 12:00
      Anna will come Monday at 12, she can't make Friday at 10 | 5 | 12:00
      Anna can't make it at 10, she'll come an hour later | - | 11:00
      Anna can't make it at 10, she'll be there in half an hour | - | 10:30
      Anna will be there in half an hour | - | -
      Anna would like to come on Monday at 12 | 5 | 12:00
      Anna can't make Friday, she'll come the next day | 3 | -
      Anna can't make Friday, she'll come in a week | 7,9 | -
      Anna can't make Friday, she'll come next Friday | 9 | -
      Анна не сможет в пятницу в 10, придёт в понедельник в 12 | 5 | 12:00
      Анна придёт в понедельник в 12, в пятницу в 10 она не сможет | 5 | 12:00
      Анна не успевает к 10, придёт на час позже | - | 11:00
      Анна не успевает к 10, будет через полчаса | - | 10:30
      Анна будет через полчаса | - | -
      Анна хотела бы прийти в понедельник в 12 | 5 | 12:00
      Анна не сможет в пятницу, придёт на следующий день | 3 | -
      Анна не сможет в пятницу, придёт через неделю | 7,9 | -
      Анна не сможет в пятницу, придёт в следующую пятницу | 9 | -
      Анна не зможе в п'ятницю о 10, прийде в понеділок о 12 | 5 | 12:00
      Анна прийде в понеділок о 12, в п'ятницю о 10 вона не зможе | 5 | 12:00
      Анна не встигає на 10, прийде на годину пізніше | - | 11:00
      Анна не встигає на 10, буде через пів години | - | 10:30
      Анна буде через пів години | - | -
      Анна хотіла б прийти в понеділок о 12 | 5 | 12:00
      Анна не зможе в п'ятницю, прийде наступного дня | 3 | -
      Анна не зможе в п'ятницю, прийде через тиждень | 7,9 | -
      Анна не зможе в п'ятницю, прийде наступної п'ятниці | 9 | -
      Anna nie da rady w piątek o 10, przyjdzie w poniedziałek o 12 | 5 | 12:00
      Anna przyjdzie w poniedziałek o 12, w piątek o 10 nie da rady | 5 | 12:00
      Anna nie zdąży na 10, przyjdzie godzinę później | - | 11:00
      Anna nie zdąży na 10, będzie za pół godziny | - | 10:30
      Anna będzie za pół godziny | - | -
      Anna chciałaby przyjść w poniedziałek o 12 | 5 | 12:00
      Anna nie da rady w piątek, przyjdzie następnego dnia | 3 | -
      Anna nie da rady w piątek, przyjdzie za tydzień | 7,9 | -
      Anna nie da rady w piątek, przyjdzie w następny piątek | 9 | -
      """, "move", anna)
    assertEquals(near, 36)
    // a booking on the Friday of next week (9): `Friday` is the nearest one or the booking's own — both, never the
    // nearest alone; a weekday said beside it is counted from the booking (A2e: it was the nearest one or the
    // first after the booking's day, `2,3,10`)
    val far = check("""
      Anna can't make it at 10 on Friday, she'll be here an hour earlier | 2,9 | 09:00
      Move Anna to Friday | 2,9 | -
      Anna can't make Friday, she'll come Saturday | 10 | -
      Анна не успевает в пятницу к 10, придёт на час раньше | 2,9 | 09:00
      Перенеси Анну на пятницу | 2,9 | -
      Анна не сможет в пятницу, придёт в субботу | 10 | -
      Анна не встигає в п'ятницю на 10, прийде на годину раніше | 2,9 | 09:00
      Перенеси Анну на п'ятницю | 2,9 | -
      Анна не зможе в п'ятницю, прийде в суботу | 10 | -
      Anna nie zdąży w piątek na 10, przyjdzie godzinę wcześniej | 2,9 | 09:00
      Przenieś Annę na piątek | 2,9 | -
      Anna nie da rady w piątek, przyjdzie w sobotę | 10 | -
      """, "move", Some(Booked("b2", "Anna Kowalska", "haircut", "olena", "2027-03-12", 600)))
    assertEquals(far, 12)
    // a booking next Wednesday (7, today's weekday) at 19:00: `at seven` and `Wednesday` may be the booking's own
    // or not, and the organ cannot tell — what else `at seven` may mean stays beside the new time; the day beside
    // `Wednesday` is counted from the booking (A2e: it was `0,2,9`)
    val both = check("""
      Anna can't make it at seven, she'll come at eight | - | 07:00,08:00,20:00
      Anna can't do Wednesday, she'll come Friday | 9 | -
      Анна не сможет в семь, придёт в восемь | - | 07:00,08:00,20:00
      Анна не сможет в среду, придёт в пятницу | 9 | -
      Анна не зможе о сьомій, прийде о восьмій | - | 07:00,08:00,20:00
      Анна не зможе в середу, прийде в п'ятницю | 9 | -
      Anna nie da rady o siódmej, przyjdzie o ósmej | - | 07:00,08:00,20:00
      Anna nie da rady w środę, przyjdzie w piątek | 9 | -
      """, "move", Some(Booked("b3", "Anna Kowalska", "haircut", "olena", "2027-03-10", 1140)))
    assertEquals(both, 8)
    // no move, no booking: nothing is counted from one; a weekday `in a week` is that weekday or the one after
    val booked = check("""
      Emma on Tuesday in a week at 15:00 | 6,13 | 15:00
      Эмму во вторник через неделю на 15:00 | 6,13 | 15:00
      Емму у вівторок через тиждень на 15:00 | 6,13 | 15:00
      Emmę we wtorek za tydzień na 15:00 | 6,13 | 15:00
      Emma on Friday at 10, not Monday at 12 | 2,5 | 10:00,12:00
      Emma in a week | 7 | -
      """, "book", None)
    assertEquals(booked, 6)
    // in the space: the refusal's command is reachable, and the old day and time are not what is offered alone
    val next = desk.copy(bookings = Vector(Booked("b2", "Anna Kowalska", "haircut", "olena", "2027-03-12", 600)))
    for (text, day, time) <- Vector(
      ("Anna can't make it at 10 on Friday, she'll be here an hour earlier", "2027-03-12", 540),
      ("Анна не сможет в пятницу, придёт в субботу", "2027-03-13", 600),
      ("Анна не зможе в п'ятницю о 10, прийде в понеділок о 12", "2027-03-15", 720), // A2e: it was 03-08
      ("Anna nie da rady w piątek, przyjdzie za tydzień", "2027-03-10", 600))
    do assertEquals(Laws.reach(Computed)(next, text, Command.Move("b2", day, time)), None, text)

  test("a weekday said beside the booking's own day is counted from the booking (A2e, the operator's rule)"):
    // the booking on the Friday of next week (9); today is Wednesday. Later in the booking's week; earlier in it,
    // up to three days back; four or more days back is the one after (the nearest to the booked day: a Friday
    // booking cannot tell it from the Monday week); said with `from`; with no old day named a
    // weekday is the nearest from today, as before; `next` keeps both readings, as before
    val far = Some(Booked("b2", "Anna Kowalska", "haircut", "olena", "2027-03-12", 600))
    val counted = check("""
      Anna can't make Friday, she'll come Sunday | 11 | -
      Anna can't make Friday, she'll come Wednesday | 7 | -
      Anna can't make Friday, she'll come Tuesday | 6 | -
      Anna can't make Friday, she'll come Monday | 12 | -
      Move Anna from Friday to Thursday | 8 | -
      Anna would like to come on Saturday | 3 | -
      Move Anna to Saturday | 3 | -
      Anna can't make Friday, she'll come next Saturday | 2,3,10 | -
      Анна не сможет в пятницу, придёт в воскресенье | 11 | -
      Анна не сможет в пятницу, придёт в среду | 7 | -
      Анна не сможет в пятницу, придёт во вторник | 6 | -
      Анна не сможет в пятницу, придёт в понедельник | 12 | -
      Перенеси Анну с пятницы на четверг | 8 | -
      Анна хотела бы прийти в субботу | 3 | -
      Перенеси Анну на субботу | 3 | -
      Анна не сможет в пятницу, придёт в следующую субботу | 2,3,10 | -
      Анна не зможе в п'ятницю, прийде в неділю | 11 | -
      Анна не зможе в п'ятницю, прийде в середу | 7 | -
      Анна не зможе в п'ятницю, прийде у вівторок | 6 | -
      Анна не зможе в п'ятницю, прийде в понеділок | 12 | -
      Перенеси Анну з п'ятниці на четвер | 8 | -
      Анна хотіла б прийти в суботу | 3 | -
      Перенеси Анну на суботу | 3 | -
      Анна не зможе в п'ятницю, прийде наступної суботи | 2,3,10 | -
      Anna nie da rady w piątek, przyjdzie w niedzielę | 11 | -
      Anna nie da rady w piątek, przyjdzie w środę | 7 | -
      Anna nie da rady w piątek, przyjdzie we wtorek | 6 | -
      Anna nie da rady w piątek, przyjdzie w poniedziałek | 12 | -
      Przenieś Annę z piątku na czwartek | 8 | -
      Anna chciałaby przyjść w sobotę | 3 | -
      Przenieś Annę na sobotę | 3 | -
      Anna nie da rady w piątek, przyjdzie w następną sobotę | 2,3,10 | -
      """, "move", far)
    assertEquals(counted, 32)
    // the fallbacks, A2d's choice as it was. The booking on the Monday after next (12): the Thursday of its week
    // is past the desk's days — the nearest Thursday, or what else `Monday` may mean
    val past = check("""
      Anna can't make Monday, she'll come Thursday | 1,5 | -
      Анна не сможет в понедельник, придёт в четверг | 1,5 | -
      Анна не зможе в понеділок, прийде в четвер | 1,5 | -
      Anna nie da rady w poniedziałek, przyjdzie w czwartek | 1,5 | -
      """, "move", Some(Booked("b4", "Anna Kowalska", "haircut", "olena", "2027-03-15", 600)))
    // the booking this Friday (2): the Wednesday of its week is today, and today's own weekday said bare is
    // today or a week on (A2b); its Tuesday is gone, so the nearest one
    val gone = check("""
      Anna can't make Friday, she'll come Wednesday | 0,7 | -
      Anna can't make Friday, she'll come Tuesday | 6 | -
      Анна не сможет в пятницу, придёт в среду | 0,7 | -
      Анна не сможет в пятницу, придёт во вторник | 6 | -
      Анна не зможе в п'ятницю, прийде в середу | 0,7 | -
      Анна не зможе в п'ятницю, прийде у вівторок | 6 | -
      Anna nie da rady w piątek, przyjdzie w środę | 0,7 | -
      Anna nie da rady w piątek, przyjdzie we wtorek | 6 | -
      """, "move", desk.bookings.headOption)
    assertEquals(past + gone, 12)
    // the nearest to the booked day, not its Monday week: a Monday booking (12) and a Sunday or a Saturday said
    // beside it is the day or two before
    val monday = check("""
      Anna can't make Monday, she'll come Sunday | 11 | -
      Anna can't make Monday, she'll come Saturday | 10 | -
      Анна не сможет в понедельник, придёт в воскресенье | 11 | -
      Анна не сможет в понедельник, придёт в субботу | 10 | -
      Анна не зможе в понеділок, прийде в неділю | 11 | -
      Анна не зможе в понеділок, прийде в суботу | 10 | -
      Anna nie da rady w poniedziałek, przyjdzie w niedzielę | 11 | -
      Anna nie da rady w poniedziałek, przyjdzie w sobotę | 10 | -
      """, "move", Some(Booked("b4", "Anna Kowalska", "haircut", "olena", "2027-03-15", 600)))
    assertEquals(monday, 8)
    // in the space the settled day is the only option: no reader is asked for it
    val next = desk.copy(bookings = far.toVector)
    val hole = Computed.next(next, "Anna can't make Friday, she'll come Sunday",
      Vector(okay.frames.Choice("action", "move"), okay.frames.Choice("booking", "b2")))
    assertEquals(hole.map(_.options), Some(Vector("2027-03-14")))

  test("the booked weekday `next` or `in a week` is the booking's day a week on (A2f)"):
    // the booking this Friday (2): `next Friday`, `the following Friday`, `Friday in a week` are the Friday after it
    // (9), whether the old day is named or not; another weekday `next` keeps A2d's readings; the booking on the
    // Friday of next week (9): the Friday after it is past the desk's days, so A2d's readings stay
    val anna = desk.bookings.headOption
    val near = check("""
      Move Anna to next Friday | 9 | -
      Anna can't make Friday, she'll come the following Friday | 9 | -
      Anna will come Friday in a week | 9 | -
      Перенеси Анну на следующую пятницу | 9 | -
      Анна придёт в пятницу через неделю | 9 | -
      Перенеси Анну на наступну п'ятницю | 9 | -
      Анна прийде в п'ятницю через тиждень | 9 | -
      Przenieś Annę na następny piątek | 9 | -
      Anna przyjdzie w piątek za tydzień | 9 | -
      """, "move", anna)
    val far = check("""
      Move Anna to next Friday | 2,9 | -
      Перенеси Анну на следующую пятницу | 2,9 | -
      Перенеси Анну на наступну п'ятницю | 2,9 | -
      Przenieś Annę na następny piątek | 2,9 | -
      """, "move", Some(Booked("b2", "Anna Kowalska", "haircut", "olena", "2027-03-12", 600)))
    assertEquals(near + far, 13)
    // no move: `next Friday` is as it was
    assertEquals(shown(When.days(desk, "Book Anna for next Friday", "book", None),
      d => desk.days.indexOf(d).toString), "2,9")

  test("the space with its days and times computed: a settled line has one option, an outside one none"):
    val text = "Book Emma for a haircut on Friday at 3 with Marek"
    val chosen = Commands.path(desk, Command.Book("Emma", "haircut", Some("marek"), "2027-03-05", 900))
    assertEquals(Computed.next(desk, text, chosen.take(4)).map(_.options), Some(Vector("2027-03-05")))
    assertEquals(Computed.next(desk, text, chosen.take(5)).map(_.options), Some(Vector("15:00")))
    assertEquals(Computed.close(desk, text, chosen), Commands.close(desk, text, chosen))
    // another day than the phrase's is no option any more
    assertEquals(Computed.close(desk, text, chosen.updated(4, chosen(4).copy(option = "2027-03-06"))), None)
    // a line the phrase says nothing of keeps the desk's options
    assertEquals(Computed.next(desk, "Book Emma for a haircut", chosen.take(4)),
      Commands.next(desk, "", chosen.take(4)))
    // a time the desk does not offer: the line has no option, and the phrase is not read
    val late = Reading.run(Computed)(desk, "Book Emma for a haircut tomorrow at 23:00")((_, h) =>
      h.options.map(o => if Set("book", "Emma", "haircut", "any").contains(o) then 1.0 else 0.0))
    assert(late.stuck && late.meaning.isEmpty, late)
    assertEquals(late.hole.map(_.name), Some("time"))
    // every other line is `Commands`' own
    for a <- Commands.actions; l <- Commands.lines(a) if l != "day" && l != "time" do
      val before = Commands.Action -> a
      val at = Vector(okay.frames.Choice(before._1, before._2)) ++
        Commands.lines(a).takeWhile(_ != l).map(x => okay.frames.Choice(x, Commands.options(desk, text, a, x).head))
      assertEquals(Computed.next(desk, text, at), Commands.next(desk, text, at), s"$a $l")

  /** a desk of a seed: its today, horizon, step and hours, masters and bookings differ */
  private def deskOf(r: Random): Desk =
    val today = Reports.epochDay("2026-01-01") + r.nextInt(730)
    val days = (0 until Vector(7, 14, 21)(r.nextInt(3))).toVector.map(i => Reports.ofEpoch(today + i))
    val times = ((7 + r.nextInt(4)) * 60 until (16 + r.nextInt(6)) * 60 by Vector(15, 30, 60)(r.nextInt(3))).toVector
    val masters = r.shuffle(Vector("jan" -> "Jan", "maja" -> "Maja", "olena" -> "Олена", "marek" -> "Marek"))
      .take(r.nextInt(4))
    val clients = Vector("Anna Kowalska", "Maja Lis", "Мая Белова", "Jan Sob", "Леся Бойко", "Tom Wilson")
    val refs = Vector("b12", "R-17", "QXB-7H2K9P", "n7", "K4", "ZXB-H7K2P9")
    val bookings = r.shuffle(clients.zip(refs)).take(r.nextInt(6)).map((client, ref) =>
      Booked(ref, client, services(r.nextInt(services.size))._1, masters.headOption.fold("main")(_._1),
        days(r.nextInt(days.size)), times(r.nextInt(times.size))))
    Desk(days, times, services, masters, bookings)

  test("every canonical saying is narrowed to exactly its own day and time, and its command is reachable"):
    val r = new Random(20261118)
    var said = 0
    for _ <- 1 to 120 do
      val d = deskOf(r)
      def day = d.days(r.nextInt(d.days.size))
      def time = d.times(r.nextInt(d.times.size))
      def master = if d.masters.isEmpty || r.nextBoolean() then None else Some(d.masters(r.nextInt(d.masters.size))._1)
      val commands = Vector(Command.Book(Vector("Emma", "Олю Иванову", "Ewę Lis", "Maja")(r.nextInt(4)),
        services(r.nextInt(services.size))._1, master, day, time), Command.Show(day, master)) ++
        d.bookings.headOption.toVector.flatMap(b => Vector(Command.Move(b.ref, day, time),
          Command.Move(b.ref, b.day, time), Command.Move(b.ref, day, b.time), Command.Move(b.ref, b.day, b.time))) ++
        d.masters.headOption.map(m => Command.DayOff(m._1, day))
      for c <- commands; l <- Lang.values do
        val text = Commands.say(d, c, l.code)
        val path = Commands.path(d, c)
        assertEquals(Laws.reach(Computed)(d, text, c), None, text)
        for (choice, i) <- path.zipWithIndex if choice.hole == "day" || choice.hole == "time" do
          assertEquals(Computed.next(d, text, path.take(i)).map(_.options), Some(Vector(choice.option)),
            s"${choice.hole} of: $text")
        // a reader is never asked for a day or a time of canonical words
        val read = Reading.run(Computed)(d, text) { (reading, hole) =>
          assert(hole.name != "day" && hole.name != "time", s"${hole.name} weighed: $text")
          val wanted = path(reading.chosen.size).option
          hole.options.map(o => if o == wanted then 1.0 else 0.0)
        }
        assertEquals(read.meaning, Some(c), text)
        said += 1
    assert(said > 1500, said)

  test("the words of a command still parse to it in the space computed"):
    val c = Command.Move("b1", "2027-03-08", 600)
    assertEquals(Laws.roundTrip(Computed)(desk, c, Lang.values.toVector.map(_.code)), None)
