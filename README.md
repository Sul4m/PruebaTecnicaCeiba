# Prueba Técnica Ceiba — VidaCruz

Backend reactivo para el triaje de urgencias de VidaCruz: dos microservicios Java (Spring Boot 3.5, WebFlux, Reactor) integrados por Kafka. El enunciado pide APIs y eventos; no hay frontend. Requisitos exactos, reglas clínicas, datos E1-E6 y corpus: [`docs/requirements/technical-test-baseline.md`](docs/requirements/technical-test-baseline.md).

> **Aviso:** el corpus clínico es el del ejercicio; no es una guía médica validada. No usar datos clínicos reales.

## Estado

- Local: Triage (registro, estados, SLA/escalamiento, auditoría, reporte, alertas SSE) y Asistente (RAG, abstención, resiliencia LLM, estado de urgencias) implementados; ejecución integrada con Docker Compose.
- Azure (AKS, Terraform, CI/CD, Key Vault, Event Hubs, PostgreSQL, Redis, Azure OpenAI): **Triage y Asistente desplegados y verificados** ([guía](docs/despliegue-azure.md)). Ver [URLs de Azure](#urls-de-azure).

## Ejecución local (un solo comando)

Requisitos: Docker con Compose. (JDK 17 solo si se ejecutan los servicios o las pruebas fuera de Docker.)

```bash
cp .env.example .env     # editar y definir los valores requeridos
docker compose up --build
```

Variables que `docker-compose.yml` exige (sin ellas `compose` falla con un mensaje explícito):

| Variable | Uso |
|---|---|
| `DB_USER`, `DB_PASSWORD` | Credenciales de PostgreSQL (y de Triage). `DB_NAME` opcional, defecto `triage` |
| `TRIAGE_API_KEY` | API key de Triage (cabecera `X-API-Key`) |
| `ASISTENTE_API_KEY` | API key propia del Asistente; sin ella el servicio no arranca |

> **TODO (visible):** `ASISTENTE_API_KEY` es obligatoria en `docker-compose.yml` pero aún puede faltar en `.env.example`; hasta que se agregue, añadirla manualmente a `.env`.

Se levantan: PostgreSQL 16, Kafka 3.9 (KRaft), Redis 7.4, Triage (`:8080`) y Asistente (`:8081`). Kafka queda accesible desde el host solo en `127.0.0.1:9094`.

**Proveedor LLM en local.** Sin perfil explícito el Asistente usa el perfil `dev`: dobles locales de embeddings y chat, sin credenciales ni llamadas a Azure. Sirven para ejecutar las tres consultas del baseline y las pruebas, **no** son el modelo real. El perfil `azure` (`SPRING_PROFILES_ACTIVE=azure`) usa Azure OpenAI y requiere `AZURE_OPENAI_ENDPOINT` (autenticación por Entra ID/Workload Identity en AKS; `AZURE_OPENAI_API_KEY` solo si se usa clave) y, opcionalmente, `AZURE_OPENAI_CHAT_DEPLOYMENT` (defecto `gpt-5.4-mini`) y `AZURE_OPENAI_EMBEDDING_DEPLOYMENT` (defecto `text-embedding-3-small`). El Compose actual no las pasa al contenedor; el perfil `azure` se activa en el despliegue en AKS.

Salud: `GET /actuator/health/readiness` (sin API key) en `:8080` y `:8081`. La readiness de Triage incluye PostgreSQL; la del Asistente incluye Kafka y Redis, nunca el proveedor LLM.

## Arquitectura

```mermaid
flowchart LR
    Client[Cliente API / equipo médico] -->|X-API-Key| Triage[Triage Service :8080]
    Client -->|X-API-Key| Assistant[Clinical Assistant Service :8081]
    Triage --> PG[(PostgreSQL)]
    Triage -->|outbox → triaje.eventos| Kafka[(Kafka)]
    Triage -. SSE /triage/alertas/stream .-> Client
    Kafka -->|consumidor| Assistant
    Kafka -. fallos → triaje.eventos.dlt .-> Kafka
    Assistant --> Redis[(Redis: pacientes activos por nivel)]
    Assistant -->|SimpleVectorStore en memoria| Corpus[(Corpus PROTO-001..003)]
    Assistant -->|Spring AI + Resilience4j| LLM[Azure OpenAI: gpt-5.4-mini / text-embedding-3-small]
```

- Triage escribe paciente, auditoría, alerta y outbox en **una** transacción PostgreSQL; un relay publica a Kafka después. Triage no depende de Kafka ni del Asistente.
- El Asistente no llama a Triage: reconstruye la vista de urgencias solo desde `triaje.eventos` y la guarda en Redis.
- Cada servicio es hexagonal liviano: dominio y casos de uso al centro; HTTP, scheduler y Kafka como entradas; PostgreSQL, Kafka, Redis y LLM detrás de puertos.

## Tecnologías

Java 17, Spring Boot 3.5, Spring WebFlux/Reactor, Spring Security (API key), R2DBC + PostgreSQL 16 con Flyway, Spring Kafka (Apache Kafka 3.9), Redis 7.4 reactivo (script Lua), Spring AI (Azure OpenAI) con `SimpleVectorStore`, Resilience4j, Micrometer, logs estructurados ECS, Gradle (uno por servicio), JUnit 5 + Testcontainers, Docker/Compose. Azure: AKS, Terraform, GitHub Actions (OIDC), Key Vault, Event Hubs, Managed Redis.

## Decisiones

| ADR | Decisión |
|---|---|
| [0001](docs/adr/0001-arquitectura-hexagonal.md) | Arquitectura hexagonal liviana por microservicio |
| [0002](docs/adr/0002-alertas-medicas-sse.md) | RF-06: alertas por SSE persistidas, con recuperación |
| [0003](docs/adr/0003-spring-ai-y-modelo-openai.md) | Spring AI, `gpt-5.4-mini` en Azure, `text-embedding-3-small`, `SimpleVectorStore` |
| [0004](docs/adr/0004-aks-terraform-y-despliegue.md) | AKS + Terraform + pipeline |
| [0005](docs/adr/0005-outbox-y-contrato-kafka.md) | Outbox transaccional y contrato Kafka versionado |
| [0006](docs/adr/0006-resiliencia-proveedor-llm.md) | Circuit breaker y bulkhead por operación del LLM, fallback 503 |
| [0007](docs/adr/0007-proyeccion-redis-de-urgencias.md) | Proyección de urgencias en Redis reconstruida desde Kafka |
| [0008](docs/adr/0008-autenticacion-api-key-por-servicio.md) | API key independiente por servicio |

Documentos operativos: [eventos](docs/eventos-triaje.md), [alertas](docs/alertas.md), [estado de urgencias](docs/estado-urgencias.md), [resiliencia LLM](docs/asistente-llm-resiliencia.md).

## Eventos Kafka

Topic `triaje.eventos` (3 particiones; réplica 1 en local), clave = `patientId`; dead-letter en `triaje.eventos.dlt`. Entrega **al menos una vez**; el consumidor deduplica por `eventId` e ignora versiones menores o iguales. Tipos: `paciente_registrado`, `paciente_escalado`, `paciente_en_consulta`, `paciente_alta`. Envelope v1:

```json
{
  "eventId": "6f1c1f0e-5a43-4d57-9a43-2f0b3c0f8a11",
  "eventType": "paciente_registrado",
  "schemaVersion": 1,
  "aggregateId": "0b7c2c1e-6f77-4a38-8f4e-1d6a1f3c9b52",
  "aggregateVersion": 1,
  "occurredAt": "2026-11-08T10:15:30.123456Z",
  "payload": { "patientId": "0b7c2c1e-6f77-4a38-8f4e-1d6a1f3c9b52", "nivel": "NO_URGENTE_2", "estado": "REGISTRADO", "escalated": false }
}
```

El payload es un snapshot mínimo (sin nombre, edad, queja ni signos vitales). Detalle, reintentos, DLT y limitaciones: [`docs/eventos-triaje.md`](docs/eventos-triaje.md).

## Decisión RF-06 (alerta al equipo médico)

Publicar el evento Kafka no se considera suficiente. Al escalar, Triage persiste una alerta (`medical_alert`) en la misma transacción que el flag, la auditoría y el outbox, y la expone por:

- `GET /triage/alertas/stream`: Server-Sent Events con `id` = `deliveryCursor`, `event: alerta`, heartbeat y reanudación con `Last-Event-ID`.
- `GET /triage/alertas?despuesDe=&limite=`: consulta paginada por cursor para recuperar alertas tras una desconexión.

La entrega es al menos una vez (el cliente deduplica por `alertId`). SSE no cubre clientes desconectados ni equivale a SMS/push; la persistencia permite recuperar al reconectar. Justificación completa: [ADR-0002](docs/adr/0002-alertas-medicas-sse.md) y [`docs/alertas.md`](docs/alertas.md).

## Supuestos y ambigüedades

- **SLA y escalamiento:** escala solo en `REGISTRADO` cuando el tiempo desde el registro supera estrictamente el SLA; una sola vez. El SLA se congela en `patient.sla_minutes` al registrar. Intervalo (`triage.escalation.interval`, defecto 30 s) y SLA (`triage.sla.minutes.*`) son configurables. Anular los SLA de `URGENTE_2`/`NO_URGENTE_2` por variable de entorno no está verificado; usar archivo de propiedades.
- **RF-05:** instantes en UTC, rango `[desde, hasta)`, bucket por hora de registro; los pacientes sin entrada a consulta cuentan en total y por nivel pero no en promedio/p95 (que no se imputan). Fechas sin offset se interpretan como UTC.
- **Autenticación:** API key por servicio en `X-API-Key` (no JWT): sin identidad individual ni roles; ver ADR-0008. `/actuator/health/**` es público.
- **Vector store y embeddings:** `SimpleVectorStore` en memoria con corpus versionado (`corpus/v1`) y `text-embedding-3-small`. El umbral de similitud (0.30 con los dobles de desarrollo, 0.45 provisional en `azure`) debe calibrarse con el modelo real.
- **Abstención (RN-05):** sin coincidencia relevante se devuelve solo el aviso fijo (HTTP 200), sin invocar al modelo. Las fuentes provienen de la metadata del corpus.
- **Fallo del LLM:** HTTP 503 (ProblemDetail) sin detalle del proveedor; `estado-urgencias` no se ve afectado.
- **Proyección Redis:** Redis de un solo nodo; reconstruible por replay dentro de la retención del topic.
- **Una sola réplica del relay del outbox** (`replicas: 1` hasta agregar bloqueo en BD).
- **Métricas:** Micrometer registra conteos por nivel, escalamientos y latencia del LLM, pero Actuator expone por HTTP solo `health` y `metrics` en Triage y solo `health` en el Asistente.
- Edad/nombre de los casos E1-E6: valores válidos arbitrarios al construir requests.

## Pruebas

Requieren Docker en marcha (Testcontainers para PostgreSQL, Kafka y Redis). No usan un proveedor LLM real (dobles/mocks).

```bash
cd triage-service && ./gradlew test
cd clinical-assistant-service && ./gradlew test
```

Cubren clasificación E1-E6, transiciones válidas e inválidas, escalamiento idempotente, outbox, alertas/SSE, reporte, proyección Redis, citas RAG, abstención y fallos del LLM. No hay comando de lint documentado.

## Ejemplos de uso

Con el Compose arriba y las claves de `.env`:

```bash
export TRIAGE_API_KEY=...        # valor de .env
export ASISTENTE_API_KEY=...     # valor de .env
```

### Triage (`:8080`)

Registrar (caso E1; responde `id`, `nivel`, `slaMinutos`, `estado`, `fechaRegistro`):

```bash
curl -s -X POST localhost:8080/triage/pacientes \
  -H "X-API-Key: $TRIAGE_API_KEY" -H 'Content-Type: application/json' \
  -d '{"nombreCompleto":"Ana Perez","edad":54,"quejaPrincipal":"Dolor torácico",
       "signosVitales":{"frecuenciaCardiaca":130,"presionSistolica":180,"presionDiastolica":110,
                        "spo2":97,"temperatura":36.8,"dolor":8,"gcs":15}}'
```

Consultar y cambiar estado (`REGISTRADO` → `EN_CONSULTA` → `ALTA`; una transición inválida da 409):

```bash
ID=<id devuelto>
curl -s localhost:8080/triage/pacientes/$ID -H "X-API-Key: $TRIAGE_API_KEY"
curl -s -X PATCH localhost:8080/triage/pacientes/$ID/estado \
  -H "X-API-Key: $TRIAGE_API_KEY" -H 'Content-Type: application/json' -d '{"estado":"EN_CONSULTA"}'
```

Reporte de operación por hora (ISO 8601; `+` del offset codificado como `%2B`):

```bash
curl -s "localhost:8080/triage/reportes/operacion?desde=2026-11-08T00:00:00Z&hasta=2026-11-09T00:00:00Z" \
  -H "X-API-Key: $TRIAGE_API_KEY"
```

Alertas (RF-06): consulta paginada y stream SSE (`-N` desactiva el buffer; reanudar con `Last-Event-ID`):

```bash
curl -s "localhost:8080/triage/alertas?despuesDe=0&limite=50" -H "X-API-Key: $TRIAGE_API_KEY"
curl -N localhost:8080/triage/alertas/stream -H "X-API-Key: $TRIAGE_API_KEY" -H 'Accept: text/event-stream'
curl -N localhost:8080/triage/alertas/stream -H "X-API-Key: $TRIAGE_API_KEY" -H 'Last-Event-ID: 7'
```

Para ver un escalamiento rápido en la demo, arrancar Triage con un SLA corto (propiedad `triage.sla.minutes.CRITICO`, que acepta un archivo de propiedades) y `triage.escalation.interval` bajo.

### Asistente Clínico (`:8081`)

```bash
# Consulta RAG: respuesta con fuentes [{protocolo, seccion}]
curl -s -X POST localhost:8081/asistente/consulta \
  -H "X-API-Key: $ASISTENTE_API_KEY" -H 'Content-Type: application/json' \
  -d '{"pregunta":"¿Cuáles son las medidas iniciales ante una crisis hipertensiva?"}'

# Sin protocolo en el corpus: solo el aviso de que no existe protocolo disponible
curl -s -X POST localhost:8081/asistente/consulta \
  -H "X-API-Key: $ASISTENTE_API_KEY" -H 'Content-Type: application/json' \
  -d '{"pregunta":"¿Cuál es el tratamiento de primera línea para la diabetes tipo 2?"}'

# Pacientes activos por nivel, desde la proyección en Redis
curl -s localhost:8081/asistente/estado-urgencias -H "X-API-Key: $ASISTENTE_API_KEY"
```

Sin `X-API-Key` o con una clave incorrecta ambos servicios responden 401 con cuerpo vacío.

## URLs de Azure

Un solo host HTTPS (certificado Let's Encrypt) enruta por prefijo hacia cada servicio. Región `eastus2`.

| Servicio | URL base |
|---|---|
| Triage | `https://ptc-a01-api.eastus2.cloudapp.azure.com/triage` |
| Asistente Clínico | `https://ptc-a01-api.eastus2.cloudapp.azure.com/asistente` |

Las API keys están en Key Vault (`kv-ptc-a01`: `triage-api-key`, `asistente-api-key`); no se publican. Las URLs existen mientras el entorno esté desplegado: se elimina al terminar con `scripts/destroy-azure.sh`.

Verificado en Azure el 2026-11-08:

- Sin `X-API-Key`: 401. Registro de paciente: 201 con nivel y estado; SSE de alertas con heartbeat de 15 s.
- Las tres consultas del baseline contra `gpt-5.4-mini` / `text-embedding-3-small` reales: crisis hipertensiva responde citando `PROTO-001`; sepsis (`PROTO-002`) y deshidratación pediátrica (`PROTO-003`) también; diabetes tipo 2 y dolor torácico se abstienen (no hay protocolo en el corpus).
- Flujo integrado: registrar un paciente suma 1 al total de `/asistente/estado-urgencias` en menos de 4 s (Triage → Event Hubs → Asistente → Redis) y el alta lo resta.

Demo reproducible:

```bash
H=https://ptc-a01-api.eastus2.cloudapp.azure.com
TK=$(az keyvault secret show --vault-name kv-ptc-a01 --name triage-api-key --query value -o tsv)
AK=$(az keyvault secret show --vault-name kv-ptc-a01 --name asistente-api-key --query value -o tsv)
curl -s -X POST $H/asistente/consulta -H "X-API-Key: $AK" -H 'Content-Type: application/json' \
  -d '{"pregunta":"¿Cuáles son las medidas iniciales ante una crisis hipertensiva?"}'
curl -s $H/asistente/estado-urgencias -H "X-API-Key: $AK"
curl -s -X POST $H/triage/pacientes -H "X-API-Key: $TK" -H 'Content-Type: application/json' \
  -d '{"nombreCompleto":"Ana Perez","edad":54,"quejaPrincipal":"Dolor torácico","signosVitales":{"frecuenciaCardiaca":130,"presionSistolica":180,"presionDiastolica":110,"spo2":97,"temperatura":36.8,"dolor":8,"gcs":15}}'
```

## Estructura

```
triage-service/             Servicio Triage (Gradle propio, Dockerfile)
clinical-assistant-service/ Asistente Clínico (Gradle propio, Dockerfile)
docker-compose.yml          PostgreSQL, Kafka, Redis y ambos servicios
docs/adr/                   Decisiones de arquitectura
docs/                       Contratos y runbooks
docs/requirements/          Baseline del enunciado
```
