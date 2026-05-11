# Quarkus Gradle multi-module config leak reproducer

_Note, this reproducer has been created with the help of Claude Code._

Datasource configuration from one Gradle module's `quarkusAppPartsBuild` leaks
into another module's build when both run in the same Gradle invocation under
Quarkus **3.35.x**. The leaking module's JDBC driver class is then loaded by
`AgroalProcessor` against a classpath that doesn't contain it, producing
`ClassNotFoundException` at build time.

Regression of the class fixed (narrowly) for container-image config by
[#50567](https://github.com/quarkusio/quarkus/pull/50567) (issue
[#50495](https://github.com/quarkusio/quarkus/issues/50495)). Re-introduced by
the `EffectiveConfig` rewrite in 3.35.0
([#53600](https://github.com/quarkusio/quarkus/pull/53600) /
[#53612](https://github.com/quarkusio/quarkus/pull/53612)).

## Layout

- `module-a` — datasource `ds-a`, driver `org.postgresql.Driver`. Depends on
  `quarkus-jdbc-postgresql`, so postgresql is on its classpath.
- `module-b` — datasource `ds-b`, driver `com.mysql.cj.jdbc.Driver`. Depends on
  `quarkus-jdbc-mysql`, **not** postgresql.

Under correct config isolation, neither module needs the other module's driver.

## Versions

- Quarkus plugin: `3.35.1` (set in `gradle.properties`; change to `3.34.7` to
  confirm pre-regression behaviour passes)
- Gradle: `9.5.0` (wrapper)
- Java: 21+

## Reproduce the failure

From clean state:

```sh
./gradlew --stop && rm -rf module-a/build module-b/build
./gradlew build
```

Result on Quarkus 3.35.1:

```
> Task :module-b:quarkusAppPartsBuild FAILED

  [error]: Build step io.quarkus.agroal.deployment.AgroalProcessor#build threw
  an exception: io.quarkus.runtime.configuration.ConfigurationException:
  Unable to load the datasource driver org.postgresql.Driver for the datasource
  named 'ds-a'
  Caused by: java.lang.ClassNotFoundException: org.postgresql.Driver
      at io.quarkus.agroal.deployment.AgroalProcessor.validateBuildTimeConfig(AgroalProcessor.java:168)
```

The smoking gun is `'ds-a'` — that datasource is defined only in
`module-a/src/main/resources/application.yaml`, never in module-b. It has leaked
across the module boundary.

## Differentials (each isolates the bug)

| Variant | Result |
|---|---|
| Quarkus pin `3.34.7` in `gradle.properties`, same command | PASS |
| `./gradlew :module-a:qAPB` then `./gradlew :module-b:qAPB` (two invocations) | PASS |
| Reverse module order (`:module-b:qAPB :module-a:qAPB`) | FAIL symmetrically — module-a fails on `com.mysql.cj.jdbc.Driver` |

The leak is symmetric: whichever `quarkusAppPartsBuild` runs first pollutes
the second module's effective config.

## Hypothesis

`io.quarkus.gradle.tasks.EffectiveConfig.generateQuarkusConfigMap` was rewritten
in 3.35.0. The new config-source allow-list includes `SysPropConfigSource.NAME`.
Gradle reuses worker JVMs between `quarkusAppPartsBuild` tasks within a single
invocation; system properties set on the worker by the first task persist in
that JVM. When the second task runs on the same worker, its `EffectiveConfig`
rebuild reads those stale sysprops via `SysPropConfigSource` and they enter the
second module's effective config.

The fix in #50567 only excludes `PackageConfig` / `NativeConfig` defaults from
cross-module sharing — datasource (and other extension) config still spills.

## Notes

- `org.gradle.caching=false` in `gradle.properties` keeps the reproducer
  self-contained — no interaction with `~/.gradle/caches/`. The bug reproduces
  regardless of cache setting; caching is disabled purely for predictability
  across machines.
- Each module sets a distinct `quarkus.application.name` in its
  `application.yaml`. **This is part of the trigger condition.** Empirically,
  if both modules use the default `application.name`, the bug is masked. This
  is consistent with the hypothesis that the effective config / worker keying
  partitions on application identity.
