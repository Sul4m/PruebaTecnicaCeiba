# Puerta E2E (curl + BDD + MBT)

Pruebas de extremo a extremo "como con Postman" contra `docker compose up` real, con `bash + curl + jq` 
(sin instalar nada global).

```bash
e2e/run.sh                 # levanta el stack, corre BDD + MBT + caso LLM caido, baja el stack (down -v)
e2e/run.sh --keep          # no baja el stack al terminar
e2e/run.sh --reuse         # usa el stack ya arriba (con e2e/.generated/e2e.env)
e2e/run.sh --only "S01 S02"  # solo esos escenarios BDD
e2e/run.sh --skip-mbt --skip-llm-down --no-build
```

Requisitos: Docker (puertos 8080 y 8081 libres), `curl`, `jq`. Primera corrida: construye las imagenes
(varios minutos). Duracion tipica posterior: ~6-8 min (el SLA minimo es 1 min y hay esperas reales).

## Que hace

- `run.sh` genera un env-file sintetico en `e2e/.generated/` (ignorado por git; **no** toca `.env`), levanta el stack con
  el proyecto compose `vidacruz-e2e` y espera la readiness por healthcheck. Sale con codigo != 0 si algo falla.
- `compose.e2e.yml` (override solo de pruebas): SLA de `CRITICO` = 1 min (minimo posible, los SLA son minutos enteros) y
  escalamiento cada 2 s. Por eso en este entorno `slaMinutos` de CRITICO es 1 y no 5.
- `features/*.feature`: escenarios BDD en Gherkin (espanol). Cada `@Sxx` esta implementado en `scenarios.sh` y
  `run.sh` verifica que tags y funciones coincidan.
- `model/patient_model.json` + `model/mbt.sh`: modelo de estados `(estado, escalado)` con transiciones validas,
  invalidas (409/400) y de tiempo (SLA). El generador crea un caso por transicion (camino minimo + transicion = 100 %
  de cobertura de transiciones), lo ejecuta con curl en paralelo y comprueba en cada paso el oraculo (HTTP, `estado`,
  `flags.escalado`, alertas) y al final el conteo del Asistente (via Kafka).
- `compose.llm-down.yml`: reinicia solo el Asistente con perfil `azure` y un endpoint muerto para validar el 503 (S24).

## Limites de lo validado

El proveedor de IA local es un **doble de desarrollo** (embeddings por hashing y chat que devuelve los fragmentos). Se
valida el pipeline (recuperacion, umbral, cita, abstencion, validaciones, 503/circuit breaker), **no** la calidad de
respuesta de `gpt-5.4-mini` ni el umbral con `text-embedding-3-small`; eso solo se puede comprobar contra Azure
(historias 1.6, 3.4 y 4.4). Tampoco se valida nada del despliegue en AKS.
