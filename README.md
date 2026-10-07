# CayResim

Native Android-Foto-App (Kotlin, Jetpack Compose, CameraX 1.6) fuer das Galaxy S24+.

- Architektur: BCE in Gradle-Modulen, Regeln R1 bis R27 (Projektdoku `claude/foto-app-architekturvorgaben.md`).
- Tests: Testkonzept in `claude/foto-app-testkonzept.md`. Jeder Push laeuft durch JVM-Tests, Architekturpruefung,
  Screenshot-Vergleich, Abdeckung (mind. 90 % in pure, entity, control) und Emulator-Tests mit echter Emulator-Kamera.
- APK: Sind alle Tests gruen, legt GitHub Actions ein Release mit der APK an.

Auf dem Geraet: Einstellungen -> Selbsttest starten, Screenshot an Claude schicken.
