---
id: site-salon
lineId: line-salon
slug: salon
name: Test Salon
tagline: Strzyżemy na czas. Testowy salon.
langDefault: pl
logoText: Test Salon
color: #c08a3e
contact: salon@example.com
published: true
domain: salon.example
refPrefix: TSL
step: 15
horizon: 21
lead: 60
---

# Services (fictional: the engine's booking tests)

```yaml services
- key: cut
  name: Strzyżenie
  description: nożyczki i maszynka
  price: 60.00
  currency: PLN
  order: 1
  minutes: 45
- key: beard
  name: Broda
  description: trymer i brzytwa
  price: 40.00
  currency: PLN
  order: 2
  minutes: 30
- key: gift
  name: Voucher
  description: na prezent
  price: 100.00
  currency: PLN
  order: 3
```

# The week (Warsaw time) and its exceptions

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
- day: sat
  from: 10:00
  to: 14:00
- date: 2026-11-11
  closed: true
- date: 2026-11-07
  from: 12:00
  to: 13:00
```
