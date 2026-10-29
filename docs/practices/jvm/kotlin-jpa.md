# Kotlin Spring Data and JPA practices

These rules adapt the JetBrains Kotlin JPA guidance.

---

## Entity shape

- Use a regular Kotlin `class`. Never use a `data class` for a JPA entity.
- Put every mapped property in the primary constructor. Use nullable read-only constructor properties for database-generated values.
- Never use `lateinit` in an entity.
- Never declare mapped properties in the class body.
- Use mutable properties where JPA or product behavior changes persisted state.
- Keep transport types separate from entities.
- Use scalar IDs. Do not add JPA associations such as `@OneToMany` or `@ManyToOne`. Load related data explicitly.
- Keep `kotlin("plugin.jpa")` enabled in modules that define entities.

```kotlin
@Entity
@Table(name = "recording_tasks")
class RecordingTask(
    @Column(nullable = false)
    var recordingId: Long,
    @Column(nullable = false, insertable = false, updatable = false)
    var createdAt: LocalDateTime? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
```

## Generated values

- Represent an unpersisted generated ID with `null`. Do not use `0` as a sentinel in new entities.
- Never use `@PrePersist`, `@PreUpdate`, or another entity lifecycle callback to mutate timestamps or product state. 
- Never use provider-specific timestamp annotations such as `@CreationTimestamp`, `@UpdateTimestamp`, or `@Generated`.
- If the application owns a value, set it explicitly before saving.
- If the database owns a value, map it as a nullable, read-only constructor property. Reload the entity when the generated value is required.


## Identity

- Keep managed-entity updates inside their owning transaction.
- Keep the default reference identity unless an entity must participate in value comparisons or hashed collections.
- Never generate all-field equality for an entity.
- If equality is required, compare only non-null database IDs and use a stable hash code.
- Exclude lazy or mutable state from `equals`, `hashCode`, and `toString`.


## JSON columns

- Use Hypersistence `JsonType` for structured JSON columns. Do not create an `AttributeConverter` for ordinary JSON maps.
- Declare the SQL column as `json` in both DB migration file (e.g., Flyway) and `@Column`.
- Keep required JSON properties non-null and give them an empty collection or object default when `{}` is valid.

```kotlin
@Type(type = "io.hypersistence.utils.hibernate.type.json.JsonType")
@Column(nullable = false, columnDefinition = "json")
var params: Map<String, Any?> = emptyMap()
```


## Schema ownership

- Flyway owns every schema change.
- Do not run migration SQL directly against a database to verify a migration. Run the integration path that starts the DB server and applies migrations.
- Do not copy a new migration into `schema.sql`. That file is the historical baseline. Newer migrations are applied afterward.
- Match entity nullability, lengths, enum storage, and column names to the DB migration file.
- Use `EnumType.STRING`. Never persist enum ordinals.
- Enforce required uniqueness at the database level. An application pre-check may improve errors but cannot prevent races.
- Add indexes for measured queries and foreign-key requirements, not speculation.


## Repositories

- Prefer Spring Data JPA and if you do, define JPA repositories in Kotlin.
- Name repository interfaces after the entity plural, as `EntityPluralRepository`: `HotelsRepository`, `BookingsRepository`
- Return nullable Kotlin values instead of `Optional` from custom repository methods.
- Add query methods only when a caller needs them.
- When injecting a repository, omit the `-repository` suffix in the field name — `val users: UsersRepository`


## Queries

- Diagnose N+1 behavior from executed SQL or query counts. Do not guess from annotations.
- Fix a measured N+1 with a targeted fetch join, entity graph, batch fetch, or DTO projection.
- Do not switch associations to `EAGER` to hide lazy-loading failures.
- Do not combine collection fetch joins with pagination without proving the generated query is safe.
- Remember that bulk updates and deletes bypass managed entity state. Clear or refresh the persistence context before reading affected rows.
- With open-in-view disabled, map lazy state inside the transaction that loads it.


## Review checklist

- Is the entity a regular class with every mapped property in its primary constructor?
- Are all generated fields nullable before persistence?
- Does Flyway, rather than entity metadata, own the schema?
- Does Hypersistence map each structured JSON column?
- Do database constraints protect real uniqueness and range rules?
- Does each repository method serve an existing caller?
- Does query evidence support each fetch or index change?
