#!/usr/bin/env bash
# Puerta E2E (historia 4.5): curl + jq contra `docker compose up` real. BDD (features/*.feature) + MBT (model/).
#
# Uso: e2e/run.sh [--keep] [--no-build] [--reuse] [--only "S01 S02"] [--skip-mbt] [--skip-llm-down]
#   --keep           no baja el stack al terminar
#   --reuse          no levanta/baja nada: usa el stack ya arriba con e2e/.generated/e2e.env
#   --no-build       compose up sin --build
#   --only "S.. .."  ejecuta solo esos escenarios BDD (ids de features/*.feature)
set -uo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$E2E_DIR/.." && pwd)"
GEN="$E2E_DIR/.generated"
ENV_FILE="$GEN/e2e.env"
PROJECT="vidacruz-e2e"
KEEP=0; BUILD=1; REUSE=0; ONLY=""; SKIP_MBT=0; SKIP_LLM=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP=1;; --no-build) BUILD=0;; --reuse) REUSE=1; KEEP=1;;
    --only) ONLY="$2"; shift;; --skip-mbt) SKIP_MBT=1;; --skip-llm-down) SKIP_LLM=1;;
    *) echo "opcion desconocida: $1" >&2; exit 2;;
  esac; shift
done

for bin in docker curl jq; do command -v "$bin" >/dev/null || { echo "falta $bin" >&2; exit 2; }; done
mkdir -p "$GEN"
WORK="$(mktemp -d "$GEN/work.XXXXXX")"; export WORK

# Env-file sintetico (NO se toca .env). Se conserva entre corridas con --reuse.
if [[ ! -f "$ENV_FILE" || $REUSE -eq 0 ]]; then
  {
    echo "DB_NAME=triage"
    echo "DB_USER=triage_e2e"
    echo "DB_PASSWORD=e2e-$(openssl rand -hex 8)"
    echo "TRIAGE_API_KEY=e2e-triage-$(openssl rand -hex 12)"
    echo "ASISTENTE_API_KEY=e2e-asistente-$(openssl rand -hex 12)"
  } > "$ENV_FILE"
fi
set -a; source "$ENV_FILE"; set +a
export DB_NAME DB_USER TRIAGE_API_KEY ASISTENTE_API_KEY

DC_ARGS=(-p "$PROJECT" --env-file "$ENV_FILE" -f "$ROOT/docker-compose.yml" -f "$E2E_DIR/compose.e2e.yml")
export TRIAGE_URL="${TRIAGE_URL:-http://localhost:8080}" ASSIST_URL="${ASSIST_URL:-http://localhost:8081}"

# shellcheck source=lib/lib.sh
source "$E2E_DIR/lib/lib.sh"
# shellcheck source=scenarios.sh
source "$E2E_DIR/scenarios.sh"

cleanup() {
  local rc=$?
  if [[ $KEEP -eq 0 ]]; then
    echo "== Bajando el stack (docker compose down -v) =="
    docker compose "${DC_ARGS[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  else
    echo "== Stack conservado (--keep/--reuse): docker compose -p $PROJECT down -v =="
  fi
  jobs -p | xargs kill 2>/dev/null || true
  rm -rf "$WORK"
  exit $rc
}
trap cleanup EXIT

wait_ready() { # URL NOMBRE TIMEOUT
  local end=$((SECONDS + $3))
  while (( SECONDS < end )); do
    [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$1" 2>/dev/null)" == "200" ]] && { echo "   $2 listo"; return 0; }
    sleep 3
  done
  echo "   $2 NO quedo listo en $3 s" >&2; docker compose "${DC_ARGS[@]}" logs --tail 40 >&2; return 1
}

if [[ $REUSE -eq 0 ]]; then
  echo "== Levantando el stack (compose project $PROJECT) =="
  docker compose "${DC_ARGS[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  UPARGS=(up -d); [[ $BUILD -eq 1 ]] && UPARGS+=(--build)
  docker compose "${DC_ARGS[@]}" "${UPARGS[@]}" || { echo "compose up fallo" >&2; exit 1; }
fi
echo "== Esperando readiness por healthcheck =="
wait_ready "$TRIAGE_URL/actuator/health/readiness" triage 300 || exit 1
wait_ready "$ASSIST_URL/actuator/health/readiness" clinical-assistant 300 || exit 1

# ---------- BDD ----------
PASSED=(); FAILED=()
run_scenario() { # ID "titulo" funcion
  local id="$1" title="$2" fn="$3"
  if [[ -n "$ONLY" && " $ONLY " != *" $id "* ]]; then return; fi
  echo "-- [$id] $title"
  ( set -u; "$fn" ) > "$WORK/out.$id" 2>&1
  local rc=$?
  cat "$WORK/out.$id"
  if [[ $rc -eq 0 ]]; then echo "   PASS [$id]"; PASSED+=("$id"); else echo "   FAIL [$id]"; FAILED+=("$id"); fi
}

echo "== BDD: escenarios (features/*.feature) =="
check_feature_tags || exit 1
prepare_escalation   # registra pacientes CRITICO + abre SSE ahora para solapar la espera del SLA

run_scenario S01 "Clasificacion E1-E6 (baseline)"                  sc_e1_e6
run_scenario S02 "Fronteras de clasificacion RN-01"                sc_boundaries
run_scenario S03 "Queja administrativa no rebaja la gravedad"      sc_admin
run_scenario S04 "Respuesta de registro y SLA por nivel"           sc_register_response
run_scenario S05 "Consulta de paciente, 404 y 400"                 sc_get_patient
run_scenario S06 "Validaciones 400 con campo"                      sc_validations
run_scenario S07 "Limites validos aceptados"                       sc_valid_limits
run_scenario S08 "Sin X-API-Key se responde 401"                   sc_auth
run_scenario S09 "Transiciones validas de estado"                  sc_valid_transitions
run_scenario S10 "Transiciones invalidas 409 sin efectos"          sc_invalid_transitions
run_scenario S11 "Estado desconocido 400 y paciente inexistente 404" sc_patch_errors
run_scenario S12 "Reporte de operacion por hora"                   sc_report
run_scenario S13 "Reporte con parametros invalidos"                sc_report_invalid
run_scenario S19 "Conteo del Asistente sube al registrar y baja al alta (Kafka)" sc_kafka_counts
run_scenario S20 "Eventos en el topic triaje.eventos sin datos personales" sc_kafka_events
run_scenario S21 "Consulta RAG con cita del protocolo"             sc_rag
run_scenario S22 "Abstencion sin protocolo (RN-05)"                sc_abstention
run_scenario S23 "Consulta RAG invalida 400"                       sc_rag_invalid
# Escalamiento: esperan al SLA (1 min) que corre en paralelo desde prepare_escalation
run_scenario S14 "Escalamiento por SLA una sola vez (RN-02/03)"    sc_escalation_once
run_scenario S15 "Nunca se escala EN_CONSULTA ni ALTA"             sc_escalation_never
run_scenario S16 "Alertas persistidas GET /triage/alertas"         sc_alerts
run_scenario S17 "SSE en vivo y recuperacion con Last-Event-ID"    sc_sse
run_scenario S18 "SSE: 401 y Last-Event-ID invalido"               sc_sse_errors

# ---------- MBT ----------
MBT_RC=0
if [[ $SKIP_MBT -eq 0 && -z "$ONLY" ]]; then
  echo "== MBT: modelo de estados (e2e/model) =="
  bash "$E2E_DIR/model/mbt.sh" --triage "$TRIAGE_URL" --assistant "$ASSIST_URL" \
    --triage-key "$TRIAGE_API_KEY" --assistant-key "$ASISTENTE_API_KEY" --sla-seconds 60 \
    --model "$E2E_DIR/model/patient_model.json"
  MBT_RC=$?
fi

# ---------- Proveedor LLM caido (S24) ----------
if [[ $SKIP_LLM -eq 0 && -z "$ONLY" ]]; then
  echo "== Reiniciando el Asistente con proveedor LLM inalcanzable (perfil azure + endpoint muerto) =="
  docker compose "${DC_ARGS[@]}" -f "$E2E_DIR/compose.llm-down.yml" up -d --no-deps --force-recreate clinical-assistant >/dev/null 2>&1
  if wait_ready "$ASSIST_URL/actuator/health/readiness" "clinical-assistant (llm caido)" 240; then
    run_scenario S24 "Proveedor LLM caido responde 503 claro"      sc_llm_down
  else
    echo "-- [S24] no arranco"; FAILED+=("S24")
  fi
fi

echo
echo "================ RESUMEN ================"
echo "BDD  pasaron: ${#PASSED[@]}  ${PASSED[*]:-}"
echo "BDD  fallaron: ${#FAILED[@]}  ${FAILED[*]:-}"
[[ $SKIP_MBT -eq 0 && -z "$ONLY" ]] && echo "MBT  codigo de salida: $MBT_RC (0 = todos los casos pasaron)"
if [[ ${#FAILED[@]} -gt 0 || $MBT_RC -ne 0 ]]; then echo "RESULTADO: FALLO"; exit 1; fi
echo "RESULTADO: OK"
