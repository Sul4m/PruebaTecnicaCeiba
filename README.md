# Prueba Técnica Ceiba — VidaCruz

Backend reactivo para el triaje de urgencias de VidaCruz: dos microservicios Java (Spring Boot 3.5, WebFlux, Reactor) integrados por Kafka. El enunciado pide APIs y eventos; no hay frontend. Requisitos exactos, reglas clínicas, datos E1-E6 y corpus: [`docs/requirements/technical-test-baseline.md`](docs/requirements/technical-test-baseline.md).

> **Aviso:** el corpus clínico es el del ejercicio; no es una guía médica validada. No usar datos clínicos reales.

## Estado

- Local (ver [Ejecución local](#ejecución-local-con-docker-un-solo-comando) y [Postman](#probar-con-postman)): Triage (registro, estados, SLA/escalamiento, auditoría, reporte, alertas SSE) y Asistente (RAG, abstención, resiliencia LLM, estado de urgencias) implementados; ejecución integrada con Docker Compose.
- Azure (AKS, Terraform, CI/CD, Key Vault, Event Hubs, PostgreSQL, Redis, Azure OpenAI): **Triage y Asistente desplegados y verificados** ([guía](docs/despliegue-azure.md)). Ver [URLs de Azure](#urls-de-azure).

## Contenido

[Ejecución local con Docker](#ejecución-local-con-docker-un-solo-comando) · [Ejecución sin Docker (bootRun)](#ejecución-de-los-servicios-sin-docker-bootrun) · [Postman](#probar-con-postman) · [Ejemplos curl](#ejemplos-de-uso) · [Pruebas](#pruebas) · [Puerta E2E](#puerta-e2e-curl--bdd--mbt) · [Problemas frecuentes](#problemas-frecuentes) · [Azure](#urls-de-azure) · [Referencia de la API](#referencia-de-la-api-azure) · [CI/CD y despliegue](#cicd-y-despliegue-en-azure) · [Estructura](#estructura)

## Ejecución local con Docker (un solo comando)

**Requisitos:** Docker con Compose v2 (`docker compose version`) y los puertos `8080`, `8081` y `9094` libres. No hace falta JDK ni Gradle: las imágenes se construyen dentro de Docker.

```bash
git clone https://github.com/Sul4m/PruebaTecnicaCeiba.git && cd PruebaTecnicaCeiba
cp .env.example .env     # editar y definir los valores requeridos (ver tabla)
docker compose up --build -d
docker compose ps        # esperar a que todos estén "healthy" / "running"
```

La primera construcción tarda varios minutos (descarga Gradle y dependencias); las siguientes usan caché.

### Variables de `.env`

`docker-compose.yml` exige estas variables (sin ellas `compose` falla con un mensaje explícito). `.env` está ignorado por Git.

| Variable | Obligatoria | Uso |
|---|---|---|
| `DB_USER`, `DB_PASSWORD` | Sí | Credenciales de PostgreSQL (las usa también Triage) |
| `DB_NAME` | No (defecto `triage`) | Nombre de la base de datos |
| `TRIAGE_API_KEY` | Sí | API key de Triage (cabecera `X-API-Key`) |
| `ASISTENTE_API_KEY` | Sí | API key propia del Asistente, distinta de la de Triage; sin ella el servicio no arranca |

Ejemplo de `.env` para pruebas locales (no usar estos valores fuera de tu máquina):

```
DB_NAME=triage
DB_USER=triage
DB_PASSWORD=cambia-esto
TRIAGE_API_KEY=clave-triage-local
ASISTENTE_API_KEY=clave-asistente-local
```

### Qué se levanta

| Contenedor | Imagen | Puerto en el host |
|---|---|---|
| `postgres` | PostgreSQL 16 (volumen `triage-pgdata`) | no publicado |
| `kafka` | Apache Kafka 3.9 (KRaft, un nodo) | `127.0.0.1:9094` |
| `redis` | Redis 7.4 (volumen `assistant-redisdata`) | no publicado |
| `triage` | construida de `./triage-service` | `8080` |
| `clinical-assistant` | construida de `./clinical-assistant-service` | `8081` |

Triage espera a PostgreSQL y a Kafka saludables; el Asistente, a Kafka y Redis. Flyway crea el esquema de Triage al arrancar.

### Comandos útiles

```bash
docker compose logs -f triage clinical-assistant   # logs en vivo (JSON ECS con traceId)
docker compose ps                                  # estado y salud
docker compose restart clinical-assistant          # reiniciar un servicio
docker compose up --build -d triage                # reconstruir solo uno tras cambiar código
docker compose down                                # parar (conserva los datos)
docker compose down -v                             # parar y borrar los datos (PostgreSQL y Redis)
```

### Verificar que funciona

```bash
curl -s localhost:8080/actuator/health/readiness   # {"status":"UP"} (sin API key)
curl -s localhost:8081/actuator/health/readiness   # {"status":"UP"}
set -a; . ./.env; set +a
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/triage/alertas                                # 401 sin clave
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/triage/alertas -H "X-API-Key: $TRIAGE_API_KEY" # 200
```

La readiness de Triage incluye PostgreSQL; la del Asistente incluye Kafka y Redis, nunca el proveedor LLM.

### Proveedor LLM en local

Sin perfil explícito el Asistente usa el perfil `dev`: dobles locales de embeddings y chat, sin credenciales ni llamadas a Azure. Sirven para ejecutar las tres consultas del baseline y las pruebas, **no** son el modelo real (la calidad de `gpt-5.4-mini` solo se comprueba contra Azure). El perfil `azure` (`SPRING_PROFILES_ACTIVE=azure`) usa Azure OpenAI y requiere `AZURE_OPENAI_ENDPOINT` (autenticación por Entra ID/Workload Identity en AKS; `AZURE_OPENAI_API_KEY` solo si se usa clave) y, opcionalmente, `AZURE_OPENAI_CHAT_DEPLOYMENT` (defecto `gpt-5.4-mini`) y `AZURE_OPENAI_EMBEDDING_DEPLOYMENT` (defecto `text-embedding-3-small`). El Compose no las pasa al contenedor: el perfil `azure` se activa en el despliegue en AKS.

### Ejecución de los servicios sin Docker (bootRun)

Útil para depurar. Requiere **JDK 17 o superior**. PostgreSQL y Redis no se publican al host en el Compose, así que se levantan aparte; Kafka sí queda en `localhost:9094`, que es el valor por defecto de los servicios.

```bash
# Dependencias (una sola vez por terminal)
docker compose up -d kafka
docker run -d --name pg-local -p 5432:5432 -e POSTGRES_USER=triage -e POSTGRES_PASSWORD=triage -e POSTGRES_DB=triage postgres:16
docker run -d --name redis-local -p 6379:6379 redis:7.4

# Triage (terminal 1) — puerto 8080
cd triage-service
DB_USER=triage DB_PASSWORD=triage TRIAGE_API_KEY=clave-triage-local ./gradlew bootRun

# Asistente (terminal 2) — puerto 8081, perfil dev por defecto
cd clinical-assistant-service
ASISTENTE_API_KEY=clave-asistente-local ./gradlew bootRun --args='--server.port=8081'
```

Variables opcionales de Triage: `DB_HOST`, `DB_PORT`, `DB_NAME`, `KAFKA_BOOTSTRAP_SERVERS`. Del Asistente: `KAFKA_BOOTSTRAP_SERVERS`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL`, `ASISTENTE_KAFKA_GROUP_ID` y los ajustes de resiliencia `ASISTENTE_LLM_*`. Para limpiar: `docker rm -f pg-local redis-local`.

## Probar con Postman

Hay una colección con 9 carpetas (seguridad, registro y clasificación E1-E6, validaciones, estados, Asistente RAG, flujo integrado Triage → Kafka → Asistente → Redis, escalamiento/alertas, reporte y limpieza) y dos entornos listos, en [`docs/postman/`](docs/postman):

| Archivo | Contenido |
|---|---|
| `vidacruz.postman_collection.json` | La colección, con tests automáticos en cada petición |
| `local.postman_environment.json` | `triageUrl=http://localhost:8080`, `asistenteUrl=http://localhost:8081` |
| `azure.postman_environment.json` | Ambas URLs apuntan a `https://ptc-a01-api.eastus2.cloudapp.azure.com` |

Pasos:

1. En Postman: **Import** → seleccionar los tres archivos (no abrir la carpeta como *workspace*: Postman generaría decenas de archivos).
2. Elegir el entorno (`local` o `azure`) arriba a la derecha.
3. En el entorno, completar `triageApiKey` y `asistenteApiKey` (el valor *Current value*; son campos secretos y no se guardan en el repo):
   - local: los valores de tu `.env` (`TRIAGE_API_KEY`, `ASISTENTE_API_KEY`);
   - Azure: `az keyvault secret show --vault-name kv-ptc-a01 --name triage-api-key --query value -o tsv` (y `asistente-api-key`).
4. Ejecutar las carpetas **en orden** (menú de la colección → *Run*): algunas guardan el `id` del paciente creado para las siguientes. La carpeta 6 espera el SLA real, por lo que tarda más; la 9 cierra (da de alta) los pacientes de prueba.

`claveIncorrecta` es una variable fija usada por las pruebas de 401. En local con SLA reales, `CRITICO` escala a los 5 minutos; el Compose de la puerta E2E (más abajo) lo baja a 1 minuto para pruebas automáticas.

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
- **Vector store y embeddings:** `SimpleVectorStore` en memoria con corpus versionado (`corpus/v1`) y `text-embedding-3-small`. El umbral de similitud (0.30 con los dobles de desarrollo, 0.55 en `azure`, calibrado con el modelo real: legítimas >= 0.618, irrelevantes <= 0.503).
- **Abstención (RN-05):** sin coincidencia relevante se devuelve solo el aviso fijo (HTTP 200), sin invocar al modelo. Las fuentes provienen de la metadata del corpus.
- **Fallo del LLM:** HTTP 503 (ProblemDetail) sin detalle del proveedor; `estado-urgencias` no se ve afectado.
- **Proyección Redis:** Redis de un solo nodo; reconstruible por replay dentro de la retención del topic.
- **Una sola réplica del relay del outbox** (`replicas: 1` hasta agregar bloqueo en BD).
- **Métricas:** ambos servicios exponen `health` y `metrics` por Actuator; `/actuator/metrics` exige `X-API-Key` y las sondas de salud son públicas.
- Edad/nombre de los casos E1-E6: valores válidos arbitrarios al construir requests.

## Limitaciones conocidas

- **Relay del outbox de una sola réplica:** `OutboxRelay` no usa bloqueo entre réplicas; Kubernetes despliega Triage con `replicas: 1` y estrategia `Recreate`.
- **Auditoría inmutable por ausencia de endpoints:** no existen endpoints de modificación ni borrado, pero la base de datos no impone inmutabilidad (no hay trigger).
- **Correlación de procesos de fondo de Triage:** el scheduler de escalamiento y el relay del outbox registran logs sin `traceId` (no nacen de una petición HTTP).
- **Umbral RAG:** `ASISTENTE_RAG_THRESHOLD` (0.55 en Azure) se calibró el 2026-11-08 con `text-embedding-3-small` real: consultas legítimas puntúan >= 0.618 e irrelevantes <= 0.503. Los casos intermedios (0.58-0.59, p. ej. hipertensión crónica) los cubren el prompt (token `SIN_PROTOCOLO`) y la validación léxica `RespuestaSustentada`. El Asistente registra a nivel INFO los ids y scores recuperados para recalibrar si cambia el modelo o el corpus.

## Pruebas

Requieren JDK 17+ y Docker en marcha (Testcontainers para PostgreSQL, Kafka y Redis). No usan un proveedor LLM real (dobles/mocks).

```bash
cd triage-service && ./gradlew test
cd clinical-assistant-service && ./gradlew test
```

Cubren clasificación E1-E6, transiciones válidas e inválidas, escalamiento idempotente, outbox, alertas/SSE, reporte, proyección Redis, citas RAG, abstención y fallos del LLM. No hay comando de lint documentado.

## Puerta E2E (curl + BDD + MBT)

Pruebas de extremo a extremo contra un `docker compose` real, solo con `bash`, `curl` y `jq`:

```bash
e2e/run.sh                   # levanta el stack, corre BDD + MBT + caso LLM caído y lo baja (down -v)
e2e/run.sh --keep            # no baja el stack al terminar
e2e/run.sh --only "S01 S02"  # solo esos escenarios
```

No toca tu `.env` (genera uno sintético en `e2e/.generated/`) y usa SLA y escalamiento acelerados. Duración típica: 6-8 minutos. Detalle en [`e2e/README.md`](e2e/README.md).

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

## Referencia de la API (Azure)

Base: `https://ptc-a01-api.eastus2.cloudapp.azure.com`. Todos los endpoints exigen `X-API-Key` (la de su servicio) y devuelven `application/json`, salvo el stream SSE. Los endpoints de Actuator no se publican por el ingress.

### Triage — `/triage` (clave `triage-api-key`)

| Método | Ruta | Descripción | Respuestas |
|---|---|---|---|
| `POST` | `/triage/pacientes` | Registra y clasifica (E1-E6). Cuerpo: `nombreCompleto`, `edad`, `quejaPrincipal`, `signosVitales{frecuenciaCardiaca, presionSistolica, presionDiastolica, spo2, temperatura, dolor, gcs}` | `201` con `id`, `nivel`, `slaMinutos`, `estado`, `fechaRegistro`; `400` si un campo es inválido |
| `GET` | `/triage/pacientes/{id}` | Detalle del paciente | `200`; `404` si no existe |
| `PATCH` | `/triage/pacientes/{id}/estado` | Cambia el estado. Cuerpo: `{"estado":"EN_CONSULTA"}` | `200`; `409` si la transición no es válida; `404` |
| `GET` | `/triage/reportes/operacion?desde=&hasta=` | Operación por hora (ISO 8601, ambos obligatorios) | `200`; `400` si falta un parámetro o es inválido |
| `GET` | `/triage/alertas?despuesDe=0&limite=50` | Alertas de escalamiento paginadas (RF-06) | `200`; `400` si no es entero |
| `GET` | `/triage/alertas/stream` | Stream SSE de alertas; heartbeat cada 15 s; reanuda con `Last-Event-ID` | `200` `text/event-stream` |

Transiciones válidas: `REGISTRADO → EN_CONSULTA`, `REGISTRADO → ALTA`, `EN_CONSULTA → ALTA`. Cualquier otra responde `409`.

### Asistente Clínico — `/asistente` (clave `asistente-api-key`)

| Método | Ruta | Descripción | Respuestas |
|---|---|---|---|
| `POST` | `/asistente/consulta` | Consulta RAG sobre los protocolos. Cuerpo: `{"pregunta":"..."}`. Responde `respuesta` y `fuentes[{protocolo, seccion}]`; sin protocolo aplicable devuelve solo el aviso con `fuentes: []` | `200`; `400` si la pregunta está vacía o excede el máximo; `503` si el LLM no responde |
| `GET` | `/asistente/estado-urgencias` | Pacientes activos por nivel (proyección en Redis) | `200`; `503` si la proyección no está disponible |

### Errores

- Sin `X-API-Key` o con clave incorrecta: `401` con cuerpo vacío.
- Validación (`400`) y conflicto (`409`): `application/problem+json` (RFC 9457); el `400` nombra el campo inválido en `campo`.
- Proveedor del LLM/embeddings caído, circuito abierto o Redis no disponible: `503` (`problem+json`) en el Asistente, sin 500 ni datos inventados.

### Prueba rápida contra Azure

```bash
H=https://ptc-a01-api.eastus2.cloudapp.azure.com
TK=$(az keyvault secret show --vault-name kv-ptc-a01 --name triage-api-key --query value -o tsv)
AK=$(az keyvault secret show --vault-name kv-ptc-a01 --name asistente-api-key --query value -o tsv)

# 1. Registrar (devuelve el id)
curl -s -X POST $H/triage/pacientes -H "X-API-Key: $TK" -H 'Content-Type: application/json' \
  -d '{"nombreCompleto":"Ana Perez","edad":54,"quejaPrincipal":"Dolor torácico","signosVitales":{"frecuenciaCardiaca":130,"presionSistolica":180,"presionDiastolica":110,"spo2":97,"temperatura":36.8,"dolor":8,"gcs":15}}'
ID=<id devuelto>

# 2. Consultar, cambiar estado y dar de alta
curl -s $H/triage/pacientes/$ID -H "X-API-Key: $TK"
curl -s -X PATCH $H/triage/pacientes/$ID/estado -H "X-API-Key: $TK" -H 'Content-Type: application/json' -d '{"estado":"EN_CONSULTA"}'
curl -s -X PATCH $H/triage/pacientes/$ID/estado -H "X-API-Key: $TK" -H 'Content-Type: application/json' -d '{"estado":"ALTA"}'

# 3. Reporte, alertas y stream
curl -s "$H/triage/reportes/operacion?desde=2026-11-08T00:00:00Z&hasta=2026-11-09T00:00:00Z" -H "X-API-Key: $TK"
curl -s "$H/triage/alertas?despuesDe=0&limite=50" -H "X-API-Key: $TK"
curl -N $H/triage/alertas/stream -H "X-API-Key: $TK" -H 'Accept: text/event-stream'

# 4. Asistente: consulta con fuente, abstención y proyección
curl -s -X POST $H/asistente/consulta -H "X-API-Key: $AK" -H 'Content-Type: application/json' \
  -d '{"pregunta":"¿Qué antibiótico se administra en sepsis?"}'
curl -s -X POST $H/asistente/consulta -H "X-API-Key: $AK" -H 'Content-Type: application/json' \
  -d '{"pregunta":"¿Cuál es el tratamiento de primera línea para la diabetes tipo 2?"}'
curl -s $H/asistente/estado-urgencias -H "X-API-Key: $AK"
```

## Problemas frecuentes

| Síntoma | Causa y solución |
|---|---|
| `docker compose up` falla con "define TRIAGE_API_KEY en .env" | Falta `.env` o alguna variable obligatoria: `cp .env.example .env` y completarla |
| `port is already allocated` (8080/8081/9094) | Otro proceso usa el puerto; pararlo o cambiar el mapeo `ports:` del Compose |
| El Asistente no arranca y pide `ASISTENTE_API_KEY` | La clave es obligatoria y debe ser distinta de la de Triage |
| 401 en todas las peticiones | Falta la cabecera `X-API-Key` o se usó la clave del otro servicio |
| `estado-urgencias` devuelve 503 | Redis aún no está listo o fue reiniciado: esperar la readiness y revisar `docker compose logs clinical-assistant` |
| El conteo de `estado-urgencias` no sube tras registrar | El evento viaja por Kafka (normalmente <4 s): reintentar; si persiste, revisar `docker compose logs kafka triage` |
| Tras cambiar credenciales de BD falla el login a PostgreSQL | El volumen conserva las credenciales antiguas: `docker compose down -v` |
| Las pruebas Gradle fallan al iniciar contenedores | Testcontainers necesita Docker en marcha y accesible |
| Postman descarga/crea muchos archivos | Se abrió la carpeta como *workspace*; usar **Import** con los tres JSON |

## CI/CD y despliegue en Azure

- **Flujo de ramas:** `feature/fix → develop` (PR) y `develop → master` (PR). Commits con Conventional Commits.
- **`.github/workflows/triage.yml` (`deploy`):** en cada PR valida los manifests de Kubernetes, `terraform fmt/validate` y corre los tests de ambos servicios; al hacer push a `master` construye y publica las dos imágenes en ACR (tag inmutable = SHA del commit) y aplica los manifests en AKS. Se autentica por OIDC, sin credenciales en el repo.
- **`.github/workflows/infra.yml` (`infra`):** `terraform plan` (identidad de solo lectura) en PR y `apply` (identidad con escritura, con aprobación en el *environment* `azure-infra`) en push a `master`, solo cuando cambia `infra/`.
- **Infraestructura (Terraform, `infra/terraform/`):** AKS (2 nodos), ACR, Key Vault, PostgreSQL privado, Event Hubs (`triaje.eventos` y su DLT), Azure Managed Redis, Azure OpenAI (`gpt-5.4-mini` y `text-embedding-3-small`, solo Entra ID) e identidades por servicio (Workload Identity). Manifests en `k8s/`.
- **Despliegue desde cero:** pasos completos (estado remoto, `terraform apply`, secretos, cert-manager, ingress) en [`docs/despliegue-azure.md`](docs/despliegue-azure.md).
- **Costos:** el entorno ronda 300-350 USD/mes (~10-12 USD/día, estimación). Para eliminarlo al terminar: `cp scripts/destroy-azure.sh.example scripts/destroy-azure.sh && scripts/destroy-azure.sh <sufijo>` (pide confirmar el nombre exacto del grupo de recursos).

## Estructura

```
triage-service/             Servicio Triage (Gradle propio, Dockerfile)
clinical-assistant-service/ Asistente Clínico (Gradle propio, Dockerfile)
docker-compose.yml          PostgreSQL, Kafka, Redis y ambos servicios
e2e/                        Puerta E2E (curl + BDD + MBT)
infra/terraform/            Infraestructura de Azure
k8s/                        Manifests de Kubernetes (Triage, Asistente, plataforma)
scripts/                    Bootstrap de estado/secretos de Terraform, render de manifests, destroy
.github/workflows/          Pipelines deploy (triage.yml) e infra (infra.yml)
docs/adr/                   Decisiones de arquitectura
docs/postman/               Colección y entornos de Postman
docs/                       Contratos y runbooks
docs/requirements/          Baseline del enunciado
```
