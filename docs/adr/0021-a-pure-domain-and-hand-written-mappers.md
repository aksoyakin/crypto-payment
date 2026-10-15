# 0021. A pure domain, and mappers written by hand

- Status: Accepted
- Date: 2026-10-16

## Context
The rules a payment gateway has to keep are rules about money: an amount never travels without the asset it is
denominated in, a payment intent cannot leave its state machine, a ledger transaction balances. These rules are
worth nothing if they live in a service class next to a database call; they have to live in types that cannot be
constructed in an invalid state.

Two habits get in the way. The first is annotating domain types for everything that touches them — JPA for the
database, Jackson for the wire, Bean Validation for requests — until the aggregate is shaped by what is
convenient to persist and serialise rather than by the rules. The second is generating the translation between
those shapes with a mapper library, which is how a `Money` quietly becomes a `BigDecimal` with no asset, or a
renamed field stops being copied and nobody notices until a balance is wrong.

## Decision
- **The domain layer depends on `java.*` and Lombok, and nothing else.** No Spring, no JPA, no Jackson, no
  validation annotations, not even a logger. `DomainPurityTest` (ArchUnit) fails the build otherwise, and the
  rule covers `package-info.java`, which ArchUnit also counts as a class.
- Aggregates are created through factory methods with private constructors, have no setters, and change state
  through methods that name the transition (`markProcessing`). An invalid transition throws a domain exception
  rather than being ignored.
- Value objects are immutable records. An amount is a `Money`: a quantity in the asset's smallest unit together
  with the asset. There is no bare `BigInteger` amount crossing a boundary.
- Persistence and wire shapes are separate types in `infrastructure`, and the translation between them and the
  domain is **written by hand**. MapStruct, ModelMapper and their kind are not used.

## Consequences
- The domain is testable without a Spring context, and its tests run in milliseconds.
- Every boundary crossing of a money value is a line of code a reviewer can read. A field added to one side and
  forgotten on the other is a compile error, not a silently dropped value.
- The cost is explicit: more code than a generated mapper, and the same field names written twice. This is paid
  on purpose; mapping is where correctness is lost, so it is kept visible.
- Lombok is allowed in the domain because it removes boilerplate without changing behaviour at runtime. It is
  the one exception, and the ArchUnit rule names it.
