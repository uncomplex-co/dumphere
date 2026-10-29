# E2E frontend tests

These rules apply to UI covered by browser-driven end-to-end tests.

---

## Test IDs

To keep tests reliable and simplify agent-driven verification, use Test IDs.


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
