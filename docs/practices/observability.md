# Telemetry and observability best practices

---

## Logging

Every state change, mutation, transition, side effect, and external-system invocation must produce an `INFO` log.

Log a semantic application or domain operation, not each field assignment or database statement. The caller that owns the operation logs its outcome once, even when the operation runs several queries or DML statements.

Repository and database operations are not external-system invocations. Do not log them merely because they access the database.

Use the remaining levels by outcome:

- `ERROR`: An error or exception prevents the request or operation from completing.
- `WARN`: An error or exception occurs, but handling it allows the request or operation to complete.
- `DEBUG`: Anything else that needs to be logged.


## Activity signals

- When modelling users and authentication, always capture activity signals: `last_sign_in_at`, `last_active_at`, etc. — these are essential for support, security audits, and churn analysis
