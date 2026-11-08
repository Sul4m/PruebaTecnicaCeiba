# Instrucciones para Claude Code

@AGENTS.md

## Proyecto y ejecución

- Son dos servicios independientes: Triage y Asistente Clínico. Stack: Java 17, Spring Boot 3.5, WebFlux y Gradle.
- Iniciar Triage: `cd triage-service && ./gradlew bootRun`
- Iniciar Asistente: `cd clinical-assistant-service && ./gradlew bootRun --args='--server.port=8081'`
- Pruebas: ejecutar `./gradlew test` desde la carpeta de cada servicio. No hay comando de lint documentado.
- BMAD permanece en v6.12.4. La salida está en `_bmad-output`; no hay iniciativa activa configurada.

## Convención de modelos

- Planificación (Analyst, PM y Architect): Opus.
- Product Owner (Sofia), UX (Sally), Developer y QA: Sonnet.
- Tareas triviales: Haiku.
