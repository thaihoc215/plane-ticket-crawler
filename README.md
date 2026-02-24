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

## REST API

### POST /api/alerts — Create Alert

```bash
curl -X POST http://localhost:8080/api/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "origin": "SGN",
    "destination": "HAN",
    "tripType": "ROUND_TRIP",
    "departureDate": "2026-04-01",
    "returnDate": "2026-04-10",
    "targetPrice": 1500000,
    "returnTargetPrice": 1500000,
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
  "tripType": "ROUND_TRIP",
  "departureDate": "2026-04-01",
  "returnDate": "2026-04-10",
  "targetPrice": 1500000,
  "returnTargetPrice": 1500000,
  "userEmail": "user@example.com"
}
```

### GET /api/alerts?status={active|inactive|all} — List Alerts

```bash
curl http://localhost:8080/api/alerts?status=active
```

### PATCH /api/alerts/{id}/deactivate — Deactivate Alert

```bash
curl -X PATCH http://localhost:8080/api/alerts/1/deactivate
```

### DELETE /api/alerts/{id} — Delete Alert

```bash
curl -X DELETE http://localhost:8080/api/alerts/1
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
