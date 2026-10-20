# Java Backend Playbook

Best practices for Java-based backend services.

---

## Philosophy

_Opinionated. Not everyone will agree — that's fine._

- Code is liability — less code means less complexity, fewer things that can break, and fewer things to maintain
- Prefer pragmatic simplicity — keep things small enough to fit in a context window (yours or the AI's)
- Optimize code for future maintainability — by humans or AI agents alike
- Make things and intent explicit — do not sugar-coat ugly truth behind clever abstractions or roundabout misdirection

---

## Code style

- Use `var` — explicit types are noise when the type is obvious from context
- No `final` on local variables or method parameters — that's the linter's job, not a readability aid
- Use JSpecify; apply `@NullMarked` by default. Or use Kotlin
- Use plural package names when a package holds a collection of similar types — `controllers`, `repositories`, `listeners`
- Organize method body into logical groups separated by a blank line:
  - **Guards / early returns** — validation, auth checks, precondition failures
  - **Setup / preparation** — variable declarations, data fetching, parsing
  - **Core logic** — main algorithm steps; each distinct step is its own group
  - **State mutations** — updating fields, signals, subjects
  - **Side effects** — emitting events, calling external services, logging outcomes

**Example 1 — use case with guard, setup, and side effect:**

```java
@Transactional
Result execute(Command command) {
    var hotel = hotelRepository.findById(command.hotelId())
            .orElseThrow(() -> new EntityNotFoundException("Hotel not found"));
    if (!hotel.isAcceptingBookings()) {
        return new NotAcceptingBookings();
    }

    var guest = guestRepository.findById(command.guestId());
    var availableRoom = roomService.findAvailable(hotel, command.checkIn(), command.checkOut());

    var booking = Booking.create(guest, availableRoom, command.checkIn(), command.checkOut());
    bookingRepository.save(booking);

    eventPublisher.publish(new BookingCreatedEvent(booking.getId()));

    return new Success(booking.getId());
}
```

**Example 2 — service method with mutation and logging:**

```java
void deactivateUser(long userId) {
    var user = userRepository.findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("User not found"));
    if (!user.isActive()) {
        return;
    }

    user.deactivate();

    userRepository.save(user);

    log.info("User {} deactivated", userId);
}
```


## Architecture

- Name use cases with the `Usecase` suffix (`CreateBookingUsecase`, not `CreateBookingUseCase`)
- Use cases must not call other use cases — extract shared logic into an application or domain service
- Validation belongs at the anti-corruption layer (API boundary, message consumer, etc.) — once data is inside the app, treat it as correct; do not re-validate inside methods; the exception: explicit business invariants that must hold before entering a workflow or algorithm
- Do not catch exceptions you don't know how to handle — unhandled errors propagate naturally to logs and 500 responses; wrapping or rethrowing without a clear corrective action is noise

### Usecase structure

A use case exposes a single public method: `execute(Command command)`
- Command is a `record`
- Return type is a `sealed interface` — one `Success` record (with data payload) and one record per distinct failure
- Annotate with `@Transactional`; use `readOnly = true` when there are no mutations

```java
@Service
public class CreateBookingUsecase {

    @Transactional
    Result execute(Command command) { ... }

    record Command(long hotelId, long guestId, LocalDate checkIn, LocalDate checkOut) {}

    sealed interface Result permits Success, HotelNotFound, RoomUnavailable {}
    record Success(long bookingId) implements Result {}
    record HotelNotFound() implements Result {}
    record RoomUnavailable(LocalDate from, LocalDate to) implements Result {}
}
```

Usage in a driver adapter (e.g. REST controller):

```java
var command = new CreateBookingUsecase.Command(hotelId, guestId, checkIn, checkOut);
var result = usecase.execute(command);

return switch (result) {
    case CreateBookingUsecase.Success s -> ResponseEntity.ok(new BookingResponse(s.bookingId()));
    case CreateBookingUsecase.HotelNotFound __ -> ResponseEntity.notFound().build();
    case CreateBookingUsecase.RoomUnavailable r -> ResponseEntity.status(CONFLICT)
            .body(new ErrorResponse("Room unavailable from %s to %s".formatted(r.from(), r.to())));
};
```


## Data access

- Prefer Spring Data JPA
- No associations (`@OneToMany`, `@ManyToMany`, etc.) — load related data explicitly
- Name repository interfaces as `EntityPluralRepository` — `HotelsRepository`, `BookingsRepository`
- When injecting a repository, omit the `-repository` suffix in the field name — `UsersRepository users`


## Database

- Name tables in plural (`hotels`, `bookings`)
- Avoid repeating the table name in column names — in `hotels`, the PK is `id`, not `hotel_id`
- Name binary flags as `is_something` (`is_active`, `is_deleted`)
- Business entity and dictionary tables must have `created_at` / `updated_at` timestamps; pick one level — app or DB (`ON UPDATE`) — and be consistent
- Soft-delete is fine, but hard-delete must be supported — unbounded table growth degrades query performance and inflates storage costs; if data "might be needed later", use archival: move hard-deleted rows to a dedicated `*_archive` table instead of keeping them in the main table
- Name columns by intent, not mechanics — `recorded_at` over `created_at` for activity logs, `last_seen_at` over `updated_at` for user activity tracking
- FKs are acceptable but use them selectively — MySQL acquires extra locks on referenced tables during DML, causing lock contention at scale; FKs also complicate archival if not handled carefully; referential integrity is the app's responsibility, not the DB's
- Unique constraints are a must wherever the domain model requires uniqueness


## Tests

- Prefer Kotlin
- Structure tests as Arrange / Act / Assert
- Test behavior and intent, not structure — tests that assert on internal wiring break during refactoring without catching real regressions; heavy reliance on mocks is a sign you're testing structure
- Prefer real components in tests; fall back to test doubles when needed (typically port implementations); use mocks only as a last resort
- Maintain a centralized inventory of shared test doubles — don't implement them inline inside individual test classes


## Observability

- When modelling users and authentication, always capture activity signals: `last_sign_in_at`, `last_active_at`, etc. — these are essential for support, security audits, and churn analysis
