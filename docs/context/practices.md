# Project-level engineering practices

## Build tooling

Detekt 2.0.0-alpha.6 requires Kotlin 2.4.10 on its analysis classpath, while the
application uses Kotlin 2.3.21. Keep Detekt's Kotlin dependencies isolated through
the `detekt` configuration in `build.gradle.kts` so Spring dependency management
does not override them. When upgrading Detekt, check its required Kotlin version
and update that configuration accordingly; this does not require upgrading the
application's Kotlin version.
