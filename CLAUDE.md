# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build
mvn clean install

# Run in dev mode (H2 in-memory DB)
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Run in prod mode (PostgreSQL)
mvn spring-boot:run -Dspring-boot.run.profiles=prod

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=AlertControllerTest

# Run a single test method
mvn test -Dtest=AlertControllerTest#createAlert_returnsCreatedWithAlertId
```

## Architecture

Spring Boot 3.4.2 backend for flight price alerts. Compiles with Java 17, deploys on Java 21 for Virtual Threads (`spring.threads.virtual.enabled=true`).

### Layers

- **Controller** (`controller/`) — REST endpoints at `/api/alerts`. Uses `@Valid` for input validation.
- **Service** (`service/`) — Business logic:
  - `AlertWatcherService`: Scheduled hourly (`@Scheduled`), loads active alerts, scrapes prices, triggers emails on match.
  - `FlightScraperService`: Playwright-based headless Chromium scraping with `@Retryable` (3 attempts, exponential backoff). Queries Google Flights, Vietnam Airlines, and AirAsia, returns the lowest price.
  - `EmailService`: Sends Thymeleaf-rendered HTML emails via `JavaMailSender`.
- **Repository** (`repository/`) — Spring Data JPA. `PriceAlertRepository` with custom finders (`findByActiveTrue`, `findByActive`).
- **Model** (`model/`) — `PriceAlert` JPA entity (table: `price_alerts`) with ONE_WAY/ROUND_TRIP trip types. `FlightInfo` value object.
- **DTO** (`dto/request/`, `dto/response/`) — Java records for API request/response contracts. Factory method `.from(entity)` for entity-to-DTO conversion.
- **Exception** (`exception/`) — `GlobalExceptionHandler` (`@RestControllerAdvice`) with `AlertNotFoundException` (404) and `BadRequestException` (400).
- **Scraper** (`scraper/`) — Page Object Model classes (`GoogleFlightsPage`, `VietnamAirlinesPage`, `AirAsiaPage`) encapsulate selectors and navigation. `UserAgentRotator` for bot avoidance.

### Key Patterns

- **Page Object Model**: Each airline source has its own POM class in `scraper/` with URL templates and CSS selectors.
- **Multi-source aggregation**: `FlightScraperService` queries all sources independently, collects successes, picks lowest price via `selectBestPrice()`.
- **Retry with backoff**: `@Retryable(maxAttempts=3, backoff=@Backoff(delay=5000, multiplier=2))` on scraping methods.
- **Try-with-resources**: Playwright Browser/BrowserContext/Page are auto-closed to prevent leaks.
- **Stealth scraping**: Random User-Agent rotation, `navigator.webdriver` masking, random 2-5s sleep between interactions.

## Profiles & Database

| Profile | Database | DDL | Activated by |
|---------|----------|-----|-------------|
| `dev` (default) | H2 in-memory | `create-drop` | `-Pdev` or default |
| `prod` | PostgreSQL | `validate` | `-Pprod` |

Dev H2 console available at `/h2-console` (JDBC URL: `jdbc:h2:mem:flightcrawler`, user: `sa`, no password).

## Environment Variables (Production)

```
DATABASE_URL, DATABASE_USERNAME, DATABASE_PASSWORD
MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD
```

## Testing

Tests use the `dev` profile with H2. Controller tests use `@SpringBootTest` + `MockMvc`. Service tests use Mockito (`@ExtendWith(MockitoExtension.class)`). Mail is configured to localhost:3025 in test properties.