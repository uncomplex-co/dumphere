# Code style playbook

Language-agnostic best practices for writing code.

---

## Philosophy

_Opinionated. Not everyone will agree — that's fine._

- Code is liability — less code means less complexity, fewer things that can break, and fewer things to maintain
- Prefer pragmatic simplicity — keep things small enough to fit in a context window (yours or the AI's)
- Optimize code for future maintainability — by humans or AI agents alike
- Make things and intent explicit — do not sugar-coat ugly truth behind clever abstractions or roundabout misdirection

Before adding complexity, for a speculative or vanishingly unlikely race or security edge case, explain the concrete failure mode, likelihood, and complexity cost to the user and get their buy-in. Do not silently expand scope for theoretical robustness.

---

## Code style

- Use plural package (namespace) names when a package holds a collection of similar types — `controllers`, `repositories`, `listeners`
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