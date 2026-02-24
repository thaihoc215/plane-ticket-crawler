# Plane Ticket Crawler

## Overview

Plane Ticket Crawler is a Spring Boot backend that lets users create flight price alerts and get notified by email when a matching fare drops to or below their target price.

Main capabilities:
- Create alerts through `POST /api/alerts`
- Store alerts with Spring Data JPA
- Scrape live flight data (price, airline, duration) with Playwright
- Check alerts every hour and send HTML email notifications on price match

## Infrastructure

- **Runtime stack**: Java, Spring Boot 3.4.2
- **Concurrency**: Virtual Threads enabled with:
  - `spring.threads.virtual.enabled=true`
- **Persistence**:
  - **Dev**: H2 in-memory database (`application-dev.properties`)
  - **Prod**: PostgreSQL (`application-prod.properties`)
- **Scraping engine**: Playwright for Java + Page Object Model
- **Notifications**: JavaMailSender + Thymeleaf HTML email template
- **Resilience**:
  - Retry via `@Retryable` in scraping service
  - Browser/page lifecycle managed with try-with-resources
  - User-Agent rotation + random sleep interval for stealthier scraping

## How It Works

1. **User creates an alert**
   - Calls `POST /api/alerts` with:
     - `origin`
     - `destination`
     - `targetPrice`
     - `userEmail`
   - API validates and stores a `PriceAlert`.

2. **Scheduler runs hourly**
   - `AlertWatcherService` loads active alerts every hour (`alert.watcher.fixed-rate-ms`).

3. **Scraper fetches current fare**
   - `FlightScraperService` uses Playwright and `GoogleFlightsPage` (POM) to extract:
     - Price
     - Airline
     - Flight Duration

4. **Price comparison**
   - Current price is saved to `lastCheckedPrice`.
   - If `currentPrice <= targetPrice`, the alert is matched.

5. **Email delivery**
   - `EmailService` sends an HTML email with route and fare details.
   - Successful matches and email deliveries are logged with SLF4J.

## Quick Start

### 1) Run in development mode (H2)

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 2) Create an alert

```bash
curl -X POST http://localhost:8080/api/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "origin": "JFK",
    "destination": "LAX",
    "targetPrice": 250,
    "userEmail": "user@example.com"
  }'
```

Expected response:

```json
{
  "message": "Alert created successfully",
  "alertId": 1,
  "origin": "JFK",
  "destination": "LAX",
  "targetPrice": 250,
  "userEmail": "user@example.com"
}
```

## Configuration Notes

- Scheduler interval:
  - `alert.watcher.fixed-rate-ms=3600000` (1 hour)
- Mail settings are read from environment variables:
  - `MAIL_HOST`
  - `MAIL_PORT`
  - `MAIL_USERNAME`
  - `MAIL_PASSWORD`
