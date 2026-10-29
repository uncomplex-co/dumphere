# Java playbook

Best practices for Java-based backend services.

---

## Java code style

- Use `var` — explicit types are noise when the type is obvious from context
- Import referenced types instead of spelling package names inside method bodies or signatures. Qualify a name only to resolve an actual import collision.
- No `final` on local variables or method parameters — that's the linter's job, not a readability aid
- Use JSpecify; apply `@NullMarked` by default. Or use Kotlin
