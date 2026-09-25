# Campus E-Wallet

A campus e-wallet for top-ups, student-to-student transfers and merchant payments, with admin reports. It runs on a double-entry ledger, so every ringgit can be traced and the books always sum to zero.

> Learning and portfolio project. No real money and no payment gateway. Top-ups are simulated.

**Built with:** Java 21, Spring Boot 3.5, PostgreSQL 16, Flyway, Thymeleaf, Testcontainers.

## Why Spring Boot

This project has one requirement that can't bend: money must always be correct. That mattered more than how fast the pages could be built, and it's why Spring Boot was the pick.

- **Transactions are declarative.** One `@Transactional` on a service method wraps the locking, the checks and the posting in a single database transaction. Any exception rolls all of it back, with no manual begin or commit to forget.
- **Row locking is built in.** JPA's `PESSIMISTIC_WRITE` lock mode issues `SELECT ... FOR UPDATE` directly. The concurrency guarantee in "How money stays correct" rests on it.
- **Security comes in the box.** Spring Security provides form login, BCrypt password hashing, CSRF protection on every form and role-based URL rules. You don't have to put these together from separate packages.
- **Tests run against a real database.** Spring Boot's Testcontainers support (`@ServiceConnection`) starts a real PostgreSQL for the test suite from a single bean. Locking can't be tested on an in-memory stand-in like H2, which locks differently.
- **Java's types suit money code.** Amounts are `long` sen, form input is Java records, and entry types are enums, so the compiler catches many mistakes before the code runs.
- **It's common where money is handled.** Java and Spring are widely used in banking and payments, so the patterns here (double-entry ledger, pessimistic locking, idempotency keys) carry over to real systems.

**The trade-off:** Spring Boot takes more code and configuration than a minimal setup, and it starts slower and uses more memory. For a project where correctness under concurrency is the whole point, that cost is worth paying.

## Run it

You need Java 21+ and Docker running.

```bash
./mvnw spring-boot:run     # starts Postgres from compose.yaml, runs Flyway + dev seed, serves http://localhost:8080
./mvnw test                # real Postgres via Testcontainers
./mvnw verify
```

On Windows use `mvnw.cmd`.

## Dev accounts (dev profile only)

Every password is `campus123`.

| Role | Email | Notes |
|---|---|---|
| Admin | `admin@campus.test` | Reports, users, counter top-up, reversals |
| Merchant | `kafe@campus.test` | Kafe Siswa, code `KAFESISWA` |
| Merchant | `fotostat@campus.test` | Kedai Fotostat, code `FOTOSTAT` |
| Student | `aisyah@campus.test` | Student ID `A21CS0001` |
| Student | `weiming@campus.test` | `A21CS0002` |
| Student | `priya@campus.test` | `A21EE0003` |
| Student | `haziq@campus.test` | `A22ME0004` |
| Student | `meiling@campus.test` | `A22CS0005` |

The seed lives in `src/main/resources/db/dev/V9__seed_dev.sql` and only runs with the `dev` profile. `spring-boot:run` turns that profile on for you. A packaged jar runs without it, so the dev accounts never reach a server. To start again from a clean database, run `docker compose down -v`.

## How money stays correct

- Amounts are `long` sen. RM 12.50 is `1250`.
- `LedgerService` is the only writer of ledger lines and balances. The ledger repositories are read-only interfaces with no `save()`.
- Every operation runs in one transaction. It locks the involved accounts with `SELECT ... FOR UPDATE` in ascending id order, checks the idempotency key, checks the rules and balances, then posts lines that sum to 0.
- The database backs this up with `CHECK (balance >= 0)` on wallets, triggers that reject any UPDATE or DELETE on the ledger, and a deferred trigger that rejects any entry whose lines don't sum to 0 at commit.
- Every confirm form carries a fresh UUID idempotency key. Submitting it twice returns the original receipt.
- Reconciliation (admin reports, and a test) checks that each account's balance equals the sum of its ledger lines and that everything sums to 0.

`ConcurrencyTest` fires 50 parallel RM 5 transfers at a RM 100 wallet. Exactly 20 succeed, the balance ends at 0 and never goes negative, and reconciliation passes.
