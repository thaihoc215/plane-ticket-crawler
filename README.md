# Plane Ticket Crawler

Spring Boot backend that lets users create flight price alerts and get notified by email when fares drop to or below their target price. Scrapes live data from Google Flights, Vietnam Airlines, and AirAsia using Playwright.

## Architecture

```text
Java 21 / Spring Boot 3.4.2
Virtual Threads enabled (spring.threads.virtual.enabled=true)

src/main/java/com/planecrawler/
├── PlaneTicketCrawlerApplication.java   @EnableScheduling @EnableRetry
├── controller/
│   └── AlertController.java             REST endpoints (/api/alerts)
├── dto/
│   ├── request/
│   │   └── CreateAlertRequest.java      Validated input record
│   └── response/
│       ├── CreateAlertResponse.java     POST response with .from(entity)
│       ├── AlertResponse.java           GET list response with .from(entity)
│       ├── ApiMessageResponse.java      Generic {message, alertId}
│       └── ValidationErrorResponse.java {message, errors map}
├── model/
│   ├── PriceAlert.java                  JPA entity (table: price_alerts)
│   └── FlightInfo.java                  Record(price, airline, duration, origin, destination)
├── repository/
│   └── PriceAlertRepository.java        JpaRepository + findByActiveTrue(), findByActive(boolean)
├── service/
│   ├── AlertWatcherService.java         @Scheduled hourly polling
│   ├── FlightScraperService.java        Playwright scraping + @Retryable
│   └── EmailService.java               Thymeleaf HTML email via JavaMailSender
├── scraper/
│   ├── GoogleFlightsPage.java           POM: aria-label → JS → full-page regex fallback
│   ├── VietnamAirlinesPage.java         POM: CSS selectors → JS fallback
│   ├── AirAsiaPage.java                 POM: data-testid → JS fallback
│   ├── PriceParser.java                 Strips non-digits, returns BigDecimal
│   └── UserAgentRotator.java            Random pick from 7 browser UA strings
├── exception/
│   ├── GlobalExceptionHandler.java      @RestControllerAdvice
│   ├── AlertNotFoundException.java      → 404
│   └── BadRequestException.java         → 400
└── resources/
    ├── templates/
    │   └── price-alert-email.html       Thymeleaf HTML email template
    ├── application.properties           Base config (mail, scheduling, virtual threads)
    ├── application-dev.properties       H2 in-memory, create-drop DDL
    └── application-prod.properties      PostgreSQL, validate DDL, HikariCP tuning
```

## Data Flow

### 1. Alert Creation (User → API → DB)

```text
User
  │
  │  POST /api/alerts
  │  { origin, destination, tripType, departureDate,
  │    returnDate?, targetPrice, returnTargetPrice?, userEmail }
  │
  ▼
AlertController
  │  @Valid input → rejects missing/invalid fields (400)
  │  ROUND_TRIP validation: requires returnDate + returnTargetPrice
  │
  ▼
PriceAlertRepository.save()
  │
  ▼
H2 / PostgreSQL
  price_alerts table
  active=true, createdAt=now()
  │
  ▼
201 Created → CreateAlertResponse { alertId, origin, destination, ... }
```

### 2. Scheduled Price Check (Scheduler → Scraper → Email)

This is the core pipeline that runs every hour (configurable via `alert.watcher.fixed-rate-ms`):

```text
@Scheduled (every 1 hour, runs on virtual thread)
AlertWatcherService.checkAlerts()
  │
  │  findByActiveTrue()
  │
  ▼
┌──────────────────────────────────────────┐
│  FOR EACH active PriceAlert (sequential) │
│  processAlert(alert)                     │
└──────────────┬───────────────────────────┘
               │
               ▼
  FlightScraperService.scrape(origin, destination, departureDate)
  │
  │  @Retryable: 3 attempts, backoff 5s → 10s → 20s
  │
  │  ┌──────────────────────────────────────────────────────────┐
  │  │  Executors.newVirtualThreadPerTaskExecutor()             │
  │  │  3 virtual threads launched in parallel:                 │
  │  │                                                         │
  │  │  VThread-1                VThread-2          VThread-3   │
  │  │  ┌──────────────┐  ┌────────────────┐  ┌────────────┐  │
  │  │  │ Playwright   │  │ Playwright     │  │ Playwright │  │
  │  │  │ + Chromium   │  │ + Chromium     │  │ + Chromium │  │
  │  │  │              │  │                │  │            │  │
  │  │  │ GoogleFlights│  │ VietnamAirlines│  │ AirAsia    │  │
  │  │  │ Page Object  │  │ Page Object    │  │ Page Object│  │
  │  │  └──────┬───────┘  └───────┬────────┘  └─────┬──────┘  │
  │  │         │                  │                  │         │
  │  │         └──────────┬───────┘──────────────────┘         │
  │  │                    │                                    │
  │  │         selectBestPrice() → lowest price wins           │
  │  └──────────────────────────────────────────────────────────┘
  │
  │  Returns FlightInfo { price, airline, duration, origin, destination }
  │
  ▼
  (if ROUND_TRIP)
  FlightScraperService.scrape(destination, origin, returnDate)
  │  Same parallel pipeline for the return leg
  │
  ▼
┌─────────────────────────────────────────────────┐
│  Update DB:                                     │
│    alert.lastCheckedPrice = outbound.price       │
│    alert.lastCheckedReturnPrice = return.price   │
│    alert.lastCheckedAt = now()                   │
│    repository.save(alert)                        │
└──────────────────────┬──────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────┐
│  Price Comparison                               │
│                                                 │
│  outboundMatched = outbound.price ≤ targetPrice │
│  returnMatched   = return.price ≤ returnTarget  │
│                    (ROUND_TRIP only)             │
│                                                 │
│  Trigger email if EITHER leg matches            │
└───────────┬─────────────────┬───────────────────┘
            │                 │
       NO match          YES (either)
            │                 │
     log debug info     EmailService.sendPriceAlert()
                              │
                              ▼
                   ┌─────────────────────────┐
                   │ Thymeleaf renders HTML   │
                   │ price-alert-email.html   │
                   │                          │
                   │ JavaMailSender → SMTP    │
                   │ → user's inbox           │
                   └─────────────────────────┘
```

### 3. Scraper Detail: How Each Source Works

Each virtual thread runs an isolated Playwright → Chromium → BrowserContext → Page chain. Playwright objects are **not thread-safe**, so each source needs its own instance.

**Stealth measures applied per browser:**

- Random User-Agent from 7 browser strings (Chrome, Safari, Firefox, Edge)
- `navigator.webdriver` property masked via `addInitScript`
- Chromium flag `--disable-blink-features=AutomationControlled`
- Random 2-5 second sleep between interactions
- Debug screenshot on failure (saved to system temp dir)

```text
┌─────────────────────────────────────────────────────────────────┐
│                     Google Flights                              │
│                                                                 │
│  URL: google.com/travel/flights/search                          │
│       ?q=Flights+from+{origin}+to+{destination}[+on+{date}]    │
│                                                                 │
│  Extraction (3-tier fallback):                                  │
│    1. Aria-labels: [data-gs] [aria-label*='$'] etc.             │
│    2. JavaScript DOM: querySelectorAll('[data-gs]') + regex     │
│    3. Full page text: document.body.innerText + regex           │
│                                                                 │
│  Parses: price ($USD or VND), airline (from aria-label),        │
│          duration (Xhr Ymin format)                              │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    Vietnam Airlines                             │
│                                                                 │
│  URL: vietnamairlines.com/vn/en/book-a-trip/booking             │
│       ?tripType=1&departCity={origin}&arriveCity={destination}   │
│       &departDate={DD/MM/YYYY}&adultQty=1&childQty=0&infantQty=0│
│                                                                 │
│  Extraction (2-tier fallback):                                  │
│    1. CSS: [class*='fare-amount'], [class*='lowestPrice'] etc.  │
│    2. JavaScript: body.innerText + VND/USD regex                │
│                                                                 │
│  Default date: 30 days from today if not specified              │
│  Airline: hardcoded "Vietnam Airlines"                          │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        AirAsia                                  │
│                                                                 │
│  URL: airasia.com/flights/search                                │
│       ?origin={origin}&destination={destination}&pax=1           │
│       [&departDate={YYYY-MM-DD}]                                │
│                                                                 │
│  Extraction (2-tier fallback):                                  │
│    1. CSS: [data-testid*='fare-amount'], [class*='total-price'] │
│    2. JavaScript: body.innerText + VND/THB/MYR/USD regex        │
│                                                                 │
│  Airline: hardcoded "AirAsia"                                   │
└─────────────────────────────────────────────────────────────────┘
```

## Playwright Scraping — Deep Dive

This section explains exactly how Playwright drives a headless Chromium browser and how each tier of the extraction strategy works in `GoogleFlightsPage`.

### Browser Lifecycle (per scrape call)

Every call to `FlightScraperService.scrape()` spins up a fresh, isolated Playwright stack using try-with-resources so every resource is guaranteed to be closed — even on exception:

```text
Playwright.create()                        ← launches Playwright process
  └─ playwright.chromium().launch()        ← spawns headless Chromium
       └─ browser.newContext()             ← isolated cookie/session jar
            └─ context.newPage()           ← a single browser tab
                 │
                 ├─ page.addInitScript()   ← runs JS before any page script
                 │    └─ masks navigator.webdriver (bot-detection bypass)
                 │
                 ├─ navigate(url)          ← page.navigate() → HTTP GET
                 │
                 ├─ extractFlights()       ← tiered extraction (see below)
                 │
                 └─ [auto-close on scope exit]
```

**Context options set on every browser:**

| Option | Value | Purpose |
|---|---|---|
| `userAgent` | random from 7 UA strings | Avoids bot fingerprinting |
| `viewportSize` | 1280 × 800 | Mimics a real laptop screen |
| `locale` | `en-US` | Forces English page content |
| Chromium arg `--no-sandbox` | — | Required inside Docker/CI |
| Chromium arg `--disable-blink-features=AutomationControlled` | — | Hides automation flag from `navigator` |

---

### Navigation & Wait Strategy (`GoogleFlightsPage.navigate`)

```text
1. Build URL:
     https://www.google.com/travel/flights/search
       ?q=Flights+from+{ORIGIN}+to+{DEST}
       [+on+{YYYY-MM-DD}]            ← one-way
       [+returning+{YYYY-MM-DD}]     ← round-trip

2. page.navigate(url)
     → Chromium performs a real HTTP GET + JS rendering

3. dismissConsentBanner()
     → Clicks "Accept all" / "I agree" if a GDPR cookie
       banner appears (3-second timeout, silently ignored
       if not present)

4. Wait for flight cards:
     page.waitForSelector("[data-gs]", timeout=20s)
     └─ [data-gs] is Google Flights' stable flight card attribute
     └─ Falls back to waitForLoadState(NETWORKIDLE) if not found
```

---

### Tiered Extraction Strategy

`extractFlights()` tries three tiers in order, returning immediately on the first success. This defends against Google's frequent CSS class changes.

#### Tier 1 — JavaScript Multi-Card Extraction *(default path)*

```text
Method: extractMultipleViaJavaScript()
How:    page.evaluate( JS snippet, limit )
```

A JavaScript snippet is injected into the live browser page via `page.evaluate()`. Playwright serialises the `limit` argument and deserialises the returned array back to Java `List<Map<String,Object>>`.

**What the JS does, step by step:**

```javascript
// 1. Find ALL flight result cards
const cards = document.querySelectorAll('[data-gs]');

// 2. For each card (up to `limit`):
for (const card of cards) {

  // 3. Read the entire visible text of the card
  const text = card.innerText;

  // 4. Find a price — tries $ first, then VND/đ/₫
  const dollarMatch = text.match(/\$(\d[\d,]*)/);
  const dongMatch   = text.match(/[₫đ](\d[\d,]*)/)
                   || text.match(/(\d{1,3}(?:,\d{3})+)\s*(?:VND|đ)/i)
                   || text.match(/VND\s*(\d[\d,]*)/);
  const priceMatch  = dollarMatch || dongMatch;  // $ takes priority
  if (!priceMatch) continue;                     // skip card if no price

  // 5. Extract flight duration ("2 hr 30 min")
  const durationMatch = text.match(/(\d+\s*hr?\s*\d*\s*min?)/);

  // 6. Extract departure / arrival times ("6:00 AM – 8:30 AM")
  const timeMatch = text.match(
    /(\d{1,2}:\d{2}\s*(?:AM|PM)?)\s*[–\-]\s*(\d{1,2}:\d{2}\s*(?:AM|PM)?)/
  );

  // 7. Guess airline name — first line that is NOT a number,
  //    price symbol, duration unit, airport code, or too long
  const lines   = text.split('\n').filter(l => l.trim());
  const airline = lines.find(l =>
    !l.match(/^\d/)           &&   // not starting with a digit
    !l.match(/^[\$₫đ]/)      &&   // not a price line
    !l.match(/hr|min|stop/i) &&   // not duration/stop info
    !l.match(/^[A-Z]{3}\s/)  &&   // not an IATA airport code
    l.length > 2 && l.length < 40 // reasonable name length
  ) || 'Unknown';

  flights.push({ price, duration, airline, departureTime, arrivalTime });
}
return flights;
```

**Java side:** The returned list is cast to `List<Map<String,Object>>`, each map entry is converted to a `FlightInfo` record with `BigDecimal` price.

---

#### Tier 2 — Aria-Label DOM Selectors *(first fallback)*

```text
Method: extractViaAriaLabels()
How:    page.locator(CSS selector with aria-label attribute filters)
```

Used when Tier 1 JS returns an empty list (e.g. DOM structure changed but aria-labels are still present).

```text
Price:    page.locator("[data-gs] [aria-label*='$']")
       or page.locator("[data-gs] [aria-label*='USD']")
       or page.locator("[data-gs] [aria-label*='dong']")
       or page.locator("[data-gs] [aria-label*='VND']")
       → .first().textContent() → PriceParser.parse()

Airline:  page.locator("[data-gs]")
          → .first().getAttribute("aria-label")
          → regex: "(?:with|by|on) ([A-Za-z ...]+)"

Duration: page.locator("[data-gs] [aria-label*='Total duration']")
       or page.locator("[data-gs] [aria-label*='hr']")
       → .first().textContent()
```

**Why aria-labels are stable:** Accessibility attributes are part of Google's public contract for screen readers — they change far less often than CSS class names.

---

#### Tier 3 — Full-Page Text Regex *(last resort)*

```text
Method: extractViaFullPageText()
How:    page.evaluate( JS → document.body.innerText ) + server-side regex
```

When both previous tiers fail (e.g. heavy bot detection, blocked page), the entire page's visible text is extracted as a single string and scanned for any price and duration pattern.

```javascript
const text = document.body.innerText;
// same dollar / dong regex as Tier 1, but on the whole page
const priceMatch    = dollarMatch || dongMatch;
const durationMatch = text.match(/(\d+\s*hr?\s*\d*\s*min?)/);
return { price: priceMatch[1].replace(/,/g, ''), duration };
```

Returns only 1 `FlightInfo` with `airline = "Unknown"` since there is no card-level isolation at this point. If `price` is still `null`, an `IllegalStateException` is thrown and `@Retryable` will schedule a retry.

---

### Tier Decision Flowchart

```text
navigate(url)
      │
      ▼
extractFlights(origin, destination, limit)
      │
      ▼
 Tier 1: extractMultipleViaJavaScript()
      │
      ├── results not empty? ──YES──► return List<FlightInfo>
      │
      └── empty or exception
            │
            ▼
       Tier 2: extractViaAriaLabels()
            │
            ├── success? ──YES──► return List.of(FlightInfo)
            │
            └── exception
                  │
                  ▼
             Tier 3: extractViaFullPageText()
                  │
                  ├── success? ──YES──► return List.of(FlightInfo)
                  │
                  └── exception
                        │
                        ▼
                  return List.of()   ← @Retryable triggers retry
```

---

### PriceParser — Currency Normalisation

`PriceParser.parse(String raw)` is called after every tier to convert whatever string was extracted into a `BigDecimal`:

```text
Input examples:   "$1,234"   "1.234.000 VND"   "₫1,200,000"   "1200000"
Steps:
  1. Strip all non-digit, non-dot, non-comma chars
  2. Remove thousands separators (commas)
  3. new BigDecimal(cleaned)
```

All downstream comparisons (`price ≤ targetPrice`) use `BigDecimal.compareTo()` to avoid floating-point precision bugs.

---

## REST API

Base URL: `http://localhost:8080`

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/alerts` | Create a new price alert |
| `GET` | `/api/alerts?status=` | List alerts filtered by status |
| `PATCH` | `/api/alerts/{id}/deactivate` | Deactivate an alert |
| `POST` | `/api/alerts/check` | Manually trigger a price check for all active alerts |
| `DELETE` | `/api/alerts/{id}` | Delete an alert permanently |

---

### POST /api/alerts — Create Alert

#### One-Way alert

```bash
curl -X POST http://localhost:8080/api/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "origin": "SGN",
    "destination": "HAN",
    "tripType": "ONE_WAY",
    "departureDate": "2026-04-01",
    "targetPrice": 800000,
    "userEmail": "user@example.com"
  }'
```

Response `201 Created`:

```json
{
  "message": "Alert created successfully",
  "alertId": 1,
  "origin": "SGN",
  "destination": "HAN",
  "tripType": "ONE_WAY",
  "departureDate": "2026-04-01",
  "returnDate": null,
  "targetPrice": 800000,
  "returnTargetPrice": null,
  "roundTripTargetPrice": null,
  "userEmail": "user@example.com"
}
```

#### Round-trip alert — monitor each leg separately

Track outbound and return legs independently. An email fires when **either** leg hits its target.

```bash
curl -X POST http://localhost:8080/api/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "origin": "SGN",
    "destination": "HAN",
    "tripType": "ROUND_TRIP",
    "departureDate": "2026-04-01",
    "returnDate": "2026-04-10",
    "targetPrice": 800000,
    "returnTargetPrice": 750000,
    "userEmail": "user@example.com"
  }'
```

Response `201 Created`:

```json
{
  "message": "Alert created successfully",
  "alertId": 2,
  "origin": "SGN",
  "destination": "HAN",
  "tripType": "ROUND_TRIP",
  "departureDate": "2026-04-01",
  "returnDate": "2026-04-10",
  "targetPrice": 800000,
  "returnTargetPrice": 750000,
  "roundTripTargetPrice": null,
  "userEmail": "user@example.com"
}
```

#### Round-trip alert — monitor combined price

Track the total round-trip price. An email fires when the combined round-trip fare hits the target.

```bash
curl -X POST http://localhost:8080/api/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "origin": "SGN",
    "destination": "HAN",
    "tripType": "ROUND_TRIP",
    "departureDate": "2026-04-01",
    "returnDate": "2026-04-10",
    "targetPrice": 800000,
    "roundTripTargetPrice": 1400000,
    "userEmail": "user@example.com"
  }'
```

Response `201 Created`:

```json
{
  "message": "Alert created successfully",
  "alertId": 3,
  "origin": "SGN",
  "destination": "HAN",
  "tripType": "ROUND_TRIP",
  "departureDate": "2026-04-01",
  "returnDate": "2026-04-10",
  "targetPrice": 800000,
  "returnTargetPrice": null,
  "roundTripTargetPrice": 1400000,
  "userEmail": "user@example.com"
}
```

#### Validation errors `400 Bad Request`

```bash
# Missing required fields
curl -X POST http://localhost:8080/api/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "origin": "SGN",
    "tripType": "ROUND_TRIP",
    "departureDate": "2026-04-01",
    "targetPrice": 800000,
    "userEmail": "user@example.com"
  }'
```

```json
{
  "message": "Validation failed",
  "errors": {
    "destination": "must not be blank"
  }
}
```

```bash
# ROUND_TRIP without returnDate
curl -X POST http://localhost:8080/api/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "origin": "SGN",
    "destination": "HAN",
    "tripType": "ROUND_TRIP",
    "departureDate": "2026-04-01",
    "targetPrice": 800000,
    "userEmail": "user@example.com"
  }'
```

```json
{
  "message": "returnDate is required for ROUND_TRIP"
}
```

---

### GET /api/alerts — List Alerts

`status` query param accepts `active` (default), `inactive`, or `all`.

```bash
# Active alerts (default)
curl http://localhost:8080/api/alerts

# Active alerts (explicit)
curl http://localhost:8080/api/alerts?status=active

# Inactive / deactivated alerts
curl http://localhost:8080/api/alerts?status=inactive

# All alerts regardless of status
curl http://localhost:8080/api/alerts?status=all
```

Response `200 OK`:

```json
[
  {
    "alertId": 1,
    "origin": "SGN",
    "destination": "HAN",
    "tripType": "ONE_WAY",
    "departureDate": "2026-04-01",
    "returnDate": null,
    "targetPrice": 800000,
    "returnTargetPrice": null,
    "roundTripTargetPrice": null,
    "userEmail": "user@example.com",
    "active": true
  },
  {
    "alertId": 2,
    "origin": "SGN",
    "destination": "HAN",
    "tripType": "ROUND_TRIP",
    "departureDate": "2026-04-01",
    "returnDate": "2026-04-10",
    "targetPrice": 800000,
    "returnTargetPrice": 750000,
    "roundTripTargetPrice": null,
    "userEmail": "user@example.com",
    "active": true
  }
]
```

Invalid status value `400 Bad Request`:

```bash
curl http://localhost:8080/api/alerts?status=unknown
```

```json
{
  "message": "Invalid status filter: unknown. Valid values are: active, inactive, all"
}
```

---

### PATCH /api/alerts/{id}/deactivate — Deactivate Alert

Stops the alert from being checked in future polling cycles. The alert record is **kept** in the database.

```bash
curl -X PATCH http://localhost:8080/api/alerts/1/deactivate
```

Response `200 OK`:

```json
{
  "message": "Alert deactivated",
  "alertId": 1
}
```

Alert not found `404 Not Found`:

```bash
curl -X PATCH http://localhost:8080/api/alerts/999/deactivate
```

```json
{
  "message": "Alert not found with id: 999"
}
```

---

### POST /api/alerts/check — Manual Price Check

Immediately runs the scheduled price-check pipeline against all currently active alerts — useful for testing without waiting for the next hourly tick.

```bash
curl -X POST http://localhost:8080/api/alerts/check
```

Response `200 OK`:

```json
{
  "message": "Manual alert check completed",
  "alertsChecked": 2,
  "alertsMatched": 1,
  "results": [
    {
      "alertId": 1,
      "origin": "SGN",
      "destination": "HAN",
      "matched": true,
      "cheapestOutboundPrice": 750000,
      "targetPrice": 800000,
      "cheapestReturnPrice": null,
      "returnTargetPrice": null,
      "cheapestRoundTripPrice": null,
      "roundTripTargetPrice": null,
      "matchedOutboundFlights": [
        {
          "price": 750000,
          "airline": "VietJet Air",
          "duration": "2 hr 5 min",
          "origin": "SGN",
          "destination": "HAN"
        }
      ],
      "matchedReturnFlights": [],
      "matchedRoundTripFlights": [],
      "error": null
    },
    {
      "alertId": 2,
      "origin": "SGN",
      "destination": "DAD",
      "matched": false,
      "cheapestOutboundPrice": 1200000,
      "targetPrice": 800000,
      "cheapestReturnPrice": null,
      "returnTargetPrice": null,
      "cheapestRoundTripPrice": null,
      "roundTripTargetPrice": null,
      "matchedOutboundFlights": [],
      "matchedReturnFlights": [],
      "matchedRoundTripFlights": [],
      "error": null
    }
  ]
}
```

Round-trip alert example (per-leg mode):

```json
{
  "alertId": 3,
  "origin": "SGN",
  "destination": "HAN",
  "matched": true,
  "cheapestOutboundPrice": 780000,
  "targetPrice": 800000,
  "cheapestReturnPrice": 700000,
  "returnTargetPrice": 750000,
  "cheapestRoundTripPrice": null,
  "roundTripTargetPrice": null,
  "matchedOutboundFlights": [
    { "price": 780000, "airline": "Vietnam Airlines", "duration": "2 hr 5 min", "origin": "SGN", "destination": "HAN" }
  ],
  "matchedReturnFlights": [
    { "price": 700000, "airline": "VietJet Air", "duration": "2 hr 10 min", "origin": "HAN", "destination": "SGN" }
  ],
  "matchedRoundTripFlights": [],
  "error": null
}
```

Round-trip alert example (combined price mode):

```json
{
  "alertId": 4,
  "origin": "SGN",
  "destination": "HAN",
  "matched": true,
  "cheapestOutboundPrice": 780000,
  "targetPrice": 800000,
  "cheapestReturnPrice": null,
  "returnTargetPrice": null,
  "cheapestRoundTripPrice": 1350000,
  "roundTripTargetPrice": 1400000,
  "matchedOutboundFlights": [],
  "matchedReturnFlights": [],
  "matchedRoundTripFlights": [
    { "price": 1350000, "airline": "Vietnam Airlines", "duration": "2 hr 5 min", "origin": "SGN", "destination": "HAN" }
  ],
  "error": null
}
```

If a scraper error occurs for a specific alert, `matched` is `false` and `error` contains the message:

```json
{
  "alertId": 5,
  "origin": "SGN",
  "destination": "SIN",
  "matched": false,
  "cheapestOutboundPrice": null,
  "targetPrice": 500000,
  "cheapestReturnPrice": null,
  "returnTargetPrice": null,
  "cheapestRoundTripPrice": null,
  "roundTripTargetPrice": null,
  "matchedOutboundFlights": [],
  "matchedReturnFlights": [],
  "matchedRoundTripFlights": [],
  "error": "All flight sources failed for route SGN->SIN"
}
```

---

### DELETE /api/alerts/{id} — Delete Alert

Permanently removes the alert record from the database.

```bash
curl -X DELETE http://localhost:8080/api/alerts/1
```

Response `200 OK`:

```json
{
  "message": "Alert deleted",
  "alertId": 1
}
```

Alert not found `404 Not Found`:

```bash
curl -X DELETE http://localhost:8080/api/alerts/999
```

```json
{
  "message": "Alert not found with id: 999"
}
```

## Database Schema

**Table: `price_alerts`**

| Column                    | Type          | Constraints                  |
|---------------------------|---------------|------------------------------|
| id                        | BIGINT        | PK, auto-increment           |
| origin                    | VARCHAR       | NOT NULL                     |
| destination               | VARCHAR       | NOT NULL                     |
| trip_type                 | VARCHAR       | NOT NULL (ONE_WAY/ROUND_TRIP)|
| departure_date            | DATE          | NOT NULL                     |
| return_date               | DATE          | nullable                     |
| target_price              | DECIMAL(10,2) | NOT NULL                     |
| return_target_price       | DECIMAL(10,2) | nullable                     |
| user_email                | VARCHAR       | NOT NULL                     |
| last_checked_price        | DECIMAL(10,2) | nullable                     |
| last_checked_return_price | DECIMAL(10,2) | nullable                     |
| active                    | BOOLEAN       | NOT NULL, default true       |
| created_at                | TIMESTAMP     | NOT NULL, immutable          |
| last_checked_at           | TIMESTAMP     | nullable                     |

| Profile | Database   | DDL           |
|---------|------------|---------------|
| dev     | H2 memory  | create-drop   |
| prod    | PostgreSQL | validate      |

## Concurrency Model

```text
spring.threads.virtual.enabled=true  (Java 21)
┌─────────────────────────────────────────────────────────┐
│  Spring manages:                                        │
│   • Web requests (Tomcat) → virtual threads             │
│   • @Scheduled tasks     → virtual threads              │
└─────────────────────────────────────────────────────────┘

AlertWatcherService.checkAlerts() ← runs on 1 virtual thread
  │
  │  for each alert (sequential):
  │    processAlert(alert)
  │      │
  │      ▼
  │    FlightScraperService.scrape()
  │      │
  │      │  Executors.newVirtualThreadPerTaskExecutor()
  │      │  ┌───────────┬───────────┬───────────┐
  │      │  │ VThread-1 │ VThread-2 │ VThread-3 │
  │      │  │ Google    │ VN Air    │ AirAsia   │
  │      │  │ Flights   │ lines     │           │
  │      │  └───────────┴───────────┴───────────┘
  │      │  executor.close() blocks until all 3 complete
  │      │
  │      ▼
  │    collect results → best price → compare → email
  │
  next alert...
```

**Why separate Playwright instances per thread?**
Playwright objects (Browser, BrowserContext, Page) are **not thread-safe**. Each virtual thread gets a dedicated Playwright → Chromium → BrowserContext → Page chain. This trades slightly higher memory for true parallel execution (~3x wall-time improvement per route).

## Resilience

| Mechanism | Where | Detail |
| - | - | - |
| `@Retryable` | `FlightScraperService.scrape()` | 3 attempts, exponential backoff (5s → 10s → 20s) |
| try-with-resources | All Playwright objects | Auto-closes Browser/Context/Page on exception |
| Per-source error isolation | `scrape()` future collection | One source failing doesn't block others |
| Per-alert error isolation | `processAlert()` catch block | One alert failing doesn't stop the batch |
| Screenshot on failure | `captureDebugScreenshot()` | Timestamped PNG saved to temp dir |
| User-Agent rotation | `UserAgentRotator.random()` | 7 UA strings (Chrome, Safari, Firefox, Edge) |
| Webdriver masking | `addInitScript()` | Hides `navigator.webdriver` from bot detection |
| Random sleep | `sleepRandom()` | 2-5 second delay between page interactions |

## Quick Start

```bash
# Build
mvn clean install

# Run (dev mode, H2 in-memory)
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Run (prod mode, PostgreSQL)
mvn spring-boot:run -Dspring-boot.run.profiles=prod

# Run all tests
mvn test

# Run a single test
mvn test -Dtest=AlertControllerTest
```

## Environment Variables (Production)

```
DATABASE_URL          jdbc:postgresql://localhost:5432/flightcrawler
DATABASE_USERNAME     postgres
DATABASE_PASSWORD     (your password)

MAIL_HOST             smtp.gmail.com
MAIL_PORT             587
MAIL_USERNAME         (your email)
MAIL_PASSWORD         (app password)
```

Dev H2 console: `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:flightcrawler`, user: `sa`, no password)
