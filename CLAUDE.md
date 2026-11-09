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

## Ahorro de tokens con subagentes

- Todo subagente se lanza con `model` explícito. Implementadores y revisores (Blind Hunter, Edge Case Hunter, Verification Gap): `sonnet`. Tareas mecánicas (buscar, actualizar `sprint-status.yaml`, leer logs): `haiku` con `effort: lower`. `opus` solo si el usuario lo pide o para planificación.
- Esta regla manda sobre el paso de revisión de `bmad-build` ("misma capacidad que la sesión"). No copiar el `model` de un lanzamiento anterior: releer esta sección antes de cada lanzamiento.
- Revisión: las tres capas por defecto. En diffs menores de 30 KB basta con Edge Case Hunter y Verification Gap, que son las capas que encontraron los defectos reales.
- En rondas de parches, el subagente corre solo los tests de lo que edita; la suite completa se corre una vez, al final, y la corre quien orquesta.
- No pasar diffs ni archivos grandes por el contexto principal: escribirlos a un archivo y pasar la ruta. Clasificar hallazgos de forma breve.
- Paralelizar solo historias que no comparten archivos ni migraciones; cada una en su propio worktree y con su propia rama.
