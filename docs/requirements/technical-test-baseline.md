# Baseline funcional y técnico de la prueba VidaCruz

> Fuente: enunciado “Prueba Técnica Dev. Webflux”, fechado 2026-10-29. Este archivo transcribe y organiza los requisitos relevantes para desarrollo y pruebas; no reemplaza el enunciado contractual ni añade validación clínica. No se incluye el PDF fuente.

## Contexto y alcance

VidaCruz se presenta en el escenario como un grupo con 12 salas de urgencias. El flujo actual de triaje es manual y el enunciado identifica variación en la clasificación, poca trazabilidad de estados/tiempos y dificultad para consultar protocolos. Se pide un backend (APIs y eventos), sin frontend, compuesto por al menos dos microservicios reactivos: Triage y Asistente Clínico.

## RF-01: Registro y clasificación

`POST /triage/pacientes` recibe:

- Nombre completo, edad y queja principal.
- Frecuencia cardíaca (bpm), presión sistólica y diastólica (mmHg), SpO2 (%), temperatura (°C), dolor (0-10) y GCS (3-15).

Los rangos exactos están en “Validaciones de RF-01”. Un dato fuera de rango responde HTTP 400 con un mensaje que identifique el campo inválido. La respuesta correcta incluye `id`, `nivel`, `slaMinutos`, estado `REGISTRADO` y `fechaRegistro` ISO 8601. Publica `paciente_registrado` en `triaje.eventos`.

## RF-02: Estados y consulta de paciente

`PATCH /triage/pacientes/{id}/estado` recibe `{ "estado": "..." }`. Estados: `REGISTRADO`, `EN_CONSULTA`, `ALTA`. Transiciones permitidas:

- `REGISTRADO` → `EN_CONSULTA`
- `REGISTRADO` → `ALTA` (alta directa)
- `EN_CONSULTA` → `ALTA`

Una transición inválida responde HTTP 409 con un mensaje descriptivo. Cada transición válida genera auditoría y el evento `paciente_en_consulta` o `paciente_alta`, según corresponda.

`GET /triage/pacientes/{id}` devuelve datos completos, estado actual, flags (por ejemplo, escalado) y tiempo en el estado actual.

## RF-03: SLA y escalamiento

Un proceso periódico evalúa pacientes activos `REGISTRADO`. Al superar el SLA, marca persistentemente el flag de escalado, publica `paciente_escalado` y registra auditoría. El intervalo de evaluación y los SLA deben ser configurables para permitir pruebas rápidas.

## RF-04: Asistente Clínico

### Consulta RAG

`POST /asistente/consulta`, cuerpo `{ "pregunta": "..." }`, responde `{ "respuesta": "...", "fuentes": [{ "protocolo": "PROTO-XXX", "seccion": "..." }] }`.

La respuesta clínica debe citar una fuente válida del corpus mínimo que sigue. Si no hay coincidencia relevante, la respuesta debe limitarse al aviso de que no existe un protocolo disponible, sin contenido clínico generado.

### Conteo de urgencias

`GET /asistente/estado-urgencias` informa el número de pacientes activos (estado distinto de `ALTA`) por nivel de triaje. El Asistente reconstruye la vista consumiendo `triaje.eventos` y la persiste en Redis o una base NoSQL elegida. No llama a la API de Triage para construirla.

## RF-05: Reporte de operación

`GET /triage/reportes/operacion?desde=...&hasta=...` recibe fechas ISO 8601 y agrupa por hora: total de registrados, conteo por nivel, promedio y p95 del tiempo desde registro hasta la primera entrada a consulta. El cálculo se hace con SQL avanzado (por ejemplo, funciones de ventana, `RANK`/`ROW_NUMBER`, agregados filtrados o subconsultas correlacionadas/equivalente); no se permite iterar en memoria sobre todos los registros para agregar.

## RF-06: Alerta al equipo médico

El sistema alerta al equipo médico cuando se escala un paciente. Canal, formato y tecnología quedan abiertos: deben elegirse, implementarse y justificarse en README o ADR. No asumir que publicar el evento Kafka satisface por sí solo esta alerta sin justificarlo.

## RN-01: Clasificación por gravedad

Evaluar niveles en orden de gravedad; si se cumplen condiciones de varios niveles, prevalece el más grave. Comparadores y límites son inclusivos/exclusivos tal como se indican:

| Nivel | Nombre | Condición (cualquiera) |
|---|---|---|
| 1 | `CRITICO` | FC < 40 o FC > 120; SpO2 < 90; presión sistólica < 90; GCS ≤ 8 |
| 2 | `URGENTE` | 90 ≤ SpO2 ≤ 93; 90 ≤ sistólica ≤ 100; temperatura ≥ 38.5; 9 ≤ GCS ≤ 13 |
| 3 | `URGENTE_2` | 90 ≤ FC ≤ 120; 38.0 ≤ temperatura < 38.5; dolor ≥ 7 |
| 4 | `NO_URGENTE` | No cumple condiciones de niveles 1-3 |
| 5 | `NO_URGENTE_2` | Cumple nivel 4 y la queja principal está en la lista administrativa; operacionalmente asignar nivel 5 solo después de descartar niveles 1-3 |

La lista administrativa se encuentra más abajo. No reclasificar una queja administrativa como nivel 5 si cumple un nivel de mayor gravedad.

## RN-02: SLA

El tiempo se mide desde el registro hasta entrar a consulta; al superarlo ocurre el escalamiento.

| Nivel | SLA |
|---|---:|
| `CRITICO` | 5 min |
| `URGENTE` | 10 min |
| `URGENTE_2` | 30 min |
| `NO_URGENTE` | 60 min |
| `NO_URGENTE_2` | 120 min |

## RN-03 a RN-05: Invariantes

- **RN-03, idempotencia:** un paciente se escala una sola vez. Repetir el proceso no publica otro evento ni duplica auditoría. Nunca escalar si está `EN_CONSULTA` o `ALTA`.
- **RN-04, auditoría:** registrar registro inicial y todo cambio de estado o flag (entrada a consulta, escalamiento, alta) con timestamp, id de paciente, estado anterior, estado nuevo y tipo de evento. No debe existir endpoint API para borrar auditoría.
- **RN-05, anti-alucinación:** toda respuesta clínica contiene al menos una fuente válida del corpus. Si el protocolo citado no existe en el corpus, la respuesta es inválida. Sin coincidencia relevante, devolver únicamente el aviso de “no existe protocolo disponible”, sin contenido clínico.

## Validaciones de RF-01

| Campo | Rango válido |
|---|---|
| Frecuencia cardíaca | 20-250 bpm |
| Presión sistólica | 60-260 mmHg |
| Presión diastólica | 30-160 mmHg y siempre menor que la sistólica |
| SpO2 | 70-100 % |
| Temperatura | 34.0-42.0 °C |
| Dolor | 0-10 |
| GCS | 3-15 |
| Edad | 0-120 años |
| Nombre | 3-100 caracteres |

## Datos de referencia E1-E6

Todos los números de signos vitales se usan tal como aparecen en la tabla del enunciado. El nivel esperado es el resultado de referencia.

| Caso | FC | Sistólica | Diastólica | SpO2 | Temp. °C | Dolor | GCS | Queja principal | Nivel esperado |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| E1 | 130 | 180 | 110 | 97 | 36.8 | 8 | 15 | Dolor torácico | `CRITICO` |
| E2 | 95 | 150 | 95 | 91 | 37.0 | 4 | 15 | Dificultad para respirar | `URGENTE` |
| E3 | 88 | 130 | 85 | 98 | 38.2 | 6 | 15 | Fiebre | `URGENTE_2` |
| E4 | 72 | 120 | 80 | 99 | 36.5 | 2 | 15 | Herida por cortar | `NO_URGENTE` |
| E5 | 70 | 115 | 75 | 99 | 36.4 | 0 | 15 | Renovación de recetario | `NO_URGENTE_2` |
| E6 | 35 | 80 | 50 | 85 | 35.9 | 0 | 6 | Inconsciente | `CRITICO` |

La tabla de prueba también depende de la edad y del nombre, que no aparecen en esta tabla; completar esos campos con valores válidos al construir requests de prueba.

## Quejas administrativas para nivel 5

`documentos`, `recolección de resultados`, `renovación de recetario`, `vacunación`, `seguimiento`.

## Corpus clínico mínimo proporcionado

Este texto se incluye únicamente para reproducir el corpus del ejercicio; no se presenta como guía clínica validada ni se debe ampliar con contenido inventado.

### PROTO-001 — Crisis Hipertensiva, manejo inicial

- Definición: presión arterial ≥ 180/120 mmHg. Si hay daño de órgano diana es emergencia; si no, urgencia.
- Medidas iniciales: reconfirmar la presión tras 5 minutos de reposo. No reducir la presión más de un 25% en la primera hora. Vigilar signos de encefalopatía.
- Contraindicaciones: nifedipino sublingual. Reducción intravenosa rápida sin monitorización en cuidados intensivos.

### PROTO-002 — Sepsis, identificación temprana (qSOFA)

- Criterios: 2 o más de frecuencia respiratoria ≥ 22, alteración del estado mental, presión sistólica ≤ 100 mmHg.
- Acciones iniciales: lactato y hemocultivos antes del antibiótico. Antibiótico de amplio espectro en la primera hora. Fluidoterapia con 30 ml/kg de cristaloides si hay hipotensión o lactato ≥ 4.

### PROTO-003 — Deshidratación pediátrica

- Clasificación: leve (pérdida 3-5% del peso), moderada (5-8%), severa (>8% o alteración del estado mental).
- Rehidratación: leve o moderada sin vómito → solución oral 50 ml/kg en 4 horas. Severa → vía intravenosa, bolo de 20 ml/kg.

### Consultas RAG de prueba

1. “¿Cuáles son las medidas iniciales ante una crisis hipertensiva?” → contenido de PROTO-001 y cita de fuente.
2. “¿Cuándo se administra el primer antibiótico en sepsis?” → contenido de PROTO-002 y cita de fuente.
3. “¿Cuál es el tratamiento de primera línea para la diabetes tipo 2?” → no hay protocolo; responder que no existe protocolo disponible, sin contenido clínico generado.

## Requisitos técnicos y entregables

- Java + Spring Boot 3 + Spring WebFlux y Reactor (`Mono`/`Flux`) en los dos servicios. R2DBC reactivo para PostgreSQL. Llamadas bloqueantes en event loop se penalizan.
- Kafka integra ambos servicios con el topic `triaje.eventos` y los cuatro eventos `paciente_registrado`, `paciente_escalado`, `paciente_en_consulta` y `paciente_alta`. Formato de mensaje libre, documentar.
- Resilience4j Circuit Breaker para llamadas al proveedor LLM; fallback explícito HTTP 503 con mensaje claro. Triage nunca depende de la disponibilidad del Asistente.
- RAG con Spring AI y modelo de generación OpenAI GPT-5.4-mini servido por Azure OpenAI/Microsoft Foundry. La integración debe conservar comportamiento reactivo (por ejemplo, streaming y composición Reactor), con Resilience4j alrededor de llamadas al proveedor. El vector store y el modelo de embeddings quedan pendientes de decisión.
- Observabilidad: health/readiness probes, logs estructurados con correlación, Micrometer para conteos por nivel, escalamientos y latencia LLM; OpenTelemetry es bonus.
- Seguridad: credenciales nunca hardcodeadas; API key LLM por variable de entorno. Autenticación JWT o API key, decisión por documentar. Validar entradas.
- Tests unitarios e integración; LLM mockeable. Testcontainers PostgreSQL/Kafka se recomienda, no es obligatorio. Casos mínimos: E1-E6, transiciones, escalamiento idempotente, citas RAG y abstención sin coincidencia.
- Ejecución local con un `docker-compose` y un solo comando que levante PostgreSQL, Kafka (o equivalente), Redis y ambos servicios.
- Manifests Kubernetes: Deployment, Service y probes desplegados en AKS.
- Entrega: repositorio GitHub con código completo, README con arquitectura/diagrama/tecnologías/decisiones/ejecución, tests, URL de APIs desplegadas y accesibles en Azure, manifests, IaC con Terraform y mínimo dos ADR. La URL confirma que el despliegue funcional es parte del entregable; no basta con dejar manifests preparados.
- Plazo indicado: 3 días. Se evalúan reglas de negocio, microservicios, Reactor/Kafka/backpressure, RAG/citas/abstención, resiliencia, observabilidad, seguridad, pruebas, infraestructura y documentación.

## Decisiones tomadas para esta solución

- Arquitectura hexagonal liviana por microservicio: [ADR-0001](../adr/0001-arquitectura-hexagonal.md).
- RF-06: alertas SSE con alertas persistidas y endpoint de recuperación en Triage: [ADR-0002](../adr/0002-alertas-medicas-sse.md).
- RAG con Spring AI y OpenAI GPT-5.4-mini servido desde Azure OpenAI/Microsoft Foundry: [ADR-0003](../adr/0003-spring-ai-y-modelo-openai.md).
- Despliegue en AKS con Terraform; IaC aprovisiona infraestructura y el pipeline aplica los manifests para publicar APIs accesibles: [ADR-0004](../adr/0004-aks-terraform-y-despliegue.md).

## Resoluciones de diseño para la prueba

Las decisiones técnicas derivadas del enunciado están resueltas en el [diseño global](../../_bmad-output/planning-artifacts/architecture/solution-design-PruebaTecnicaCeiba.md) y los ADR bajo `docs/adr/`: arquitectura hexagonal liviana; alertas SSE persistidas; Spring AI con GPT-5.4-mini, embeddings `text-embedding-3-small` y `SimpleVectorStore`; autenticación con API key por servicio; contrato Kafka versionado con outbox; semántica UTC de RF-05; y despliegue temporal en AKS con Terraform. La suscripción y región se pasan como variables al Terraform del usuario; no son preguntas abiertas del diseño. El backlog de implementación desglosa el trabajo sin cambiar las reglas exactas de este baseline.
