# Practices for database schema design

---

## Database schema design

- Name tables in plural (`hotels`, `bookings`)
- Avoid repeating the table name in column names — in `hotels`, the PK is `id`, not `hotel_id`
- Name binary flags as `is_something` (`is_active`, `is_deleted`)
- Business entity and dictionary tables must have `created_at` / `updated_at` timestamps; pick one level — app or DB (`ON UPDATE`) — and be consistent
- Soft-delete is fine, but hard-delete must be supported — unbounded table growth degrades query performance and inflates storage costs; if data "might be needed later", use archival: move hard-deleted rows to a dedicated `*_archive` table instead of keeping them in the main table
- Name columns by intent, not mechanics — `recorded_at` over `created_at` for activity logs, `last_seen_at` over `updated_at` for user activity tracking
- FKs are acceptable but use them selectively — RDBMS like MySQL acquires extra locks on referenced tables during DML, causing lock contention at scale; FKs also complicate archival if not handled carefully; referential integrity is the app's responsibility, not the DB's
- Unique constraints are a must wherever the domain model requires uniqueness
