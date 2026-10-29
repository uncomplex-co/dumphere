# Best practices for automated tests

---

## Tests

- Tautological tests are considered harmful.
- Follow Arrange, Act, Assert with separate phases. Name each test for the behavior and outcome it checks; parameterized case names must identify the scenario and operation or identifier form.
- Split tests across focused classes and files when useful. Do not bundle unrelated operations merely to reuse an expensive fixture.
- Each test invocation checks one aspect. Prefer one assertion when sufficient; multiple assertions must describe the same operation's outcome, including its response, committed changes, and related side effects. Put prerequisite operations in Arrange rather than alternating Act and Assert through a workflow.
- Tests and their helpers must have one fixed execution path. No conditional branches that change Arrange, Act, or Assert. Write separate tests for different paths: success, failure, rejection, retry, or repeated operations, etc.
- Test intent and observable outcomes, not internal structure or wiring. Keep the behavior under test real and make the tested scope and mocked boundaries explicit. Tests that assert on internal wiring break during refactoring without catching real regressions.
- Use as much production code in tests as possible. Use test doubles or mocks only at edges (typically, ports). A mock's configured answer does not prove the behavior behind that mock.
- Supply expected outcomes explicitly. Do not calculate them by reproducing production decision logic.
- A rejected/no-op operation must not satisfy a success test. Check the response and independently read committed state after each lifecycle transition; unchanged-state assertions alone do not prove the operation ran.
- Maintain a centralized inventory of shared test doubles — don't implement them inline inside individual test classes.
