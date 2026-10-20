## Operation coverage

- Test intent and observable outcomes, not internal calls or wiring. Use as much production code as possible; mock only external boundaries, not the product rule being tested.
- A rejected/no-op operation must not satisfy a success test. Check the response and independently read committed state after each lifecycle transition; unchanged-state assertions alone do not prove the operation ran.
- Validate response shape before asserting empty collections. GraphQL HTTP 200 responses must also have no errors and contain the expected data and array fields.
- Preserve test-infrastructure teaching examples in `dashboard-api/src/test/java/shutter/dashboard/testing/examples/`; they demonstrate setup, not product behavior coverage. Never count a mock's configured answer as product coverage.
- Cover every changed operation through its actual use case or public API, including changes in shared code.
- For file reads, seed database object references with arbitrary buckets, paths, and names that do not match naming conventions. Verify the returned bytes and ensure that conventional-path decoys are not used.
- For file writes, exercise upload or save and inspect committed object rows for the required bucket, path, filename, and file type.
- Test normal application startup when changing dependency wiring. A standalone command or manually constructed service does not verify production application wiring.

## Test IDs

These rules apply to UI covered by browser-driven end-to-end tests.

### Add IDs to interactive elements

- Add `data-testid` to every element the user can interact with, including buttons, dropdowns, menu options, toggles, and links.
- Add `data-testid` to the element that implements the interaction. If a `div` behaves as a button, add the ID to that `div`, even when it contains a link or button.
- Do not use visible text, CSS classes, or DOM position as the primary selector for an interaction.

### Name IDs by purpose

- Use lowercase kebab case.
- Name an ID from its area, feature, and element: `area-feature-element`.
- Omit words that add no meaning. Valid examples include `cart-icon` and `checkout-submit-button`.
- Do not encode the DOM tree in an ID. Scope selectors through parent elements instead.

### Keep repeated controls stable

- Put the entity-dependent ID on the repeated row or container. Use a stable business ID: `feature-item-{stableId}`.
- Use the same test ID for a control in every row. Do not include the entity ID in each child control.
- In the test, find the row first, then find and use the control within that row.

For example, use `user-card-123` on the card and `user-delete-button` on every delete button. A test first locates `user-card-123`, then locates `user-delete-button` inside it.

### Keep one source of truth

Define test IDs once and import them in both the app and its browser tests. Split the definitions by app and test area instead of creating one repository-wide file.

```typescript
export const checkout = {
  submit: 'checkout-submit-button',
  total: 'checkout-total',
  lineItem: (id: string) => `checkout-line-item-${id}`,
} as const;
```

## Test Harness

- `test-harness-api` is a local-only Spring service on port `8084`, enabled by the `local_dev` profile and started by `mise run run-local`.
- Keep it bare minimum. Its only jobs are preparing/resetting test fixtures, emulating external side effects, and exposing state missing from public APIs.
- Never copy product rules into the harness. External-event operations must call the existing application use cases and services; repositories and entities come from `users-lib`, `studios-lib`, and the owning service modules.
- Reset operations may mutate fixture data directly, but must identify harness-owned records with `test-harness:` markers and must not truncate or rewrite seeded data.
- User-facing verification still exercises public APIs and UI flows. The harness is setup, side-effect emulation, and assertion support only.

