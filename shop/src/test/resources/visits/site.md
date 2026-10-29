---
id: site-visits
lineId: line-salon
slug: visits
name: Test Visits
tagline: Przyjdź, przyjedziemy albo zadzwonimy. Testowy warsztat.
langDefault: pl
logoText: Test Visits
color: #3e7bc0
contact: visits@example.com
published: true
domain: visits.example
refPrefix: TVS
step: 15
horizon: 21
lead: 60
---

# Services (fictional: the engine's tests of the modes)

```yaml services
- key: cut
  name: Strzyżenie
  description: u nas albo u Ciebie
  price: 60.00
  order: 1
  minutes: 45
- key: visit
  name: Naprawa w domu
  description: przyjeżdżamy z narzędziami
  price: 120.00
  order: 2
- key: consult
  name: Konsultacja
  description: wideo
  price: 50.00
  order: 3
  minutes: 30
- key: parcel
  name: Naprawa wysyłkowa
  description: wyślij paczkę
  price: 80.00
  order: 4
- key: both
  name: Ekran telefonu
  description: u nas albo paczką
  price: 200.00
  order: 5
  minutes: 60
```

```yaml schedule
- day: mon
  from: 10:00
  to: 18:00
- day: tue
  from: 10:00
  to: 18:00
- day: wed
  from: 10:00
  to: 18:00
- day: thu
  from: 10:00
  to: 18:00
- day: fri
  from: 10:00
  to: 18:00
- date: 2026-11-11
  closed: true
```

# How it works

```json work
{"modes": ["atShop", "atClient", "byMail", "online"],
 "locations": [
   {"key": "centrum", "name": "Test Visits Centrum", "street": "ul. Testowa 1", "postcode": "00-001",
    "city": "Warszawa", "notes": "II piętro, wejście od podwórza"},
   {"key": "mokotow", "name": "Test Visits Mokotów", "street": "ul. Próbna 2", "postcode": "02-002",
    "city": "Warszawa", "map": "https://maps.example/mokotow",
    "hours": [{"day": "sat", "from": "09:00", "to": "12:00"}]}
 ],
 "area": {"places": ["Warszawa", "Piaseczno"], "postcodes": ["05-5"], "fee": "30", "before": 30, "after": 30},
 "online": {"how": "video", "details": "Link: https://meet.example/test-visits"},
 "services": {
   "cut": {"modes": ["atShop", "atClient", "online"]},
   "visit": {"modes": ["atClient"], "minutes": 60, "extra": {"atClient": "20"}},
   "consult": {"modes": ["online"]},
   "parcel": {"modes": ["byMail"]},
   "both": {"modes": ["atShop", "byMail"]}
 }}
```
