# Best practices for designing backend services

---

## Architecture principles

- Follow Hexagonal architecture style, to simplify automated testing. Adapters (drivers, driven), Ports, Use cases and Application and Domain Services.
- Name use cases with the `Usecase` suffix (`CreateBookingUsecase`, not `CreateBookingUseCase`).
- Use cases must not call other use cases — extract shared logic into an application or domain service.
- Validation belongs at the anti-corruption layer (API boundary, message consumer, etc.) — once data is inside the app, treat it as correct; do not re-validate inside methods; the exception: explicit business invariants that must hold before entering a workflow or algorithm.
- Do not catch exceptions you don't know how to handle — unhandled errors propagate naturally to logs and 500 responses; wrapping or rethrowing without a clear corrective action is noise.


### Usecase structure

A use case exposes a single public method: `execute(Command command)`.
- Command is a `record` (`data class` in Kotlin).
- Return type is a `sealed interface` — one `Success` record (with data payload) and one record per distinct failure.
- A use cases is a transaction boundary. Annotate with `@Transactional`; use `readOnly = true` when there are no mutations.

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

Usage in a driver adapter (e.g. HTTP API controller):

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
