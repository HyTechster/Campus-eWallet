# Campus E-Wallet

A campus e-wallet for top-ups, student-to-student transfers and merchant payments, with admin reports. It runs on a double-entry ledger, so every ringgit can be traced and the books always sum to zero.

> Learning and portfolio project. No real money and no payment gateway. Top-ups are simulated.

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

The seed lives in `src/main/resources/db/dev/V9__seed_dev.sql` and only runs with the `dev` profile, which is the default for `spring-boot:run`. To start again from a clean database, run `docker compose down -v`.

## How money stays correct

- Amounts are `long` sen. RM 12.50 is `1250`.
- `LedgerService` is the only writer of ledger lines and balances. The ledger repositories are read-only interfaces with no `save()`.
- Every operation runs in one transaction. It locks the involved accounts with `SELECT ... FOR UPDATE` in ascending id order, checks the idempotency key, checks the rules and balances, then posts lines that sum to 0.
- The database backs this up with `CHECK (balance >= 0)` on wallets, triggers that reject any UPDATE or DELETE on the ledger, and a deferred trigger that rejects any entry whose lines don't sum to 0 at commit.
- Every confirm form carries a fresh UUID idempotency key. Submitting it twice returns the original receipt.
- Reconciliation (admin reports, and a test) checks that each account's balance equals the sum of its ledger lines and that everything sums to 0.

`ConcurrencyTest` fires 50 parallel RM 5 transfers at a RM 100 wallet. Exactly 20 succeed, the balance ends at 0 and never goes negative, and reconciliation passes.
