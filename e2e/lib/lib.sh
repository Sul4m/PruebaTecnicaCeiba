#!/usr/bin/env bash
# Helpers de la puerta E2E: curl "como en Postman" + asserts. Se carga con `source`.

TRIAGE_URL="${TRIAGE_URL:-http://localhost:8080}"
ASSIST_URL="${ASSIST_URL:-http://localhost:8081}"

# --- Pasos Gherkin (solo imprimen el paso para que el log se lea como el .feature) ---
dado()      { echo "      Given $*"; }
cuando()    { echo "      When $*"; }
entonces()  { echo "      Then $*"; }
y()         { echo "      And $*"; }

fail() { echo "      FALLO: $*" >&2; exit 1; }

# req METHOD URL [BODY] [header...]  -> STATUS, BODY (archivo $WORK/body.$$)
req() {
  local method="$1" url="$2" data="${3-}"
  shift $(( $# >= 3 ? 3 : $# ))
  local out="$WORK/body.$$"
  local args=(-sS -o "$out" -w '%{http_code}' -X "$method" --max-time 40 "$url")
  [[ -n "$data" ]] && args+=(-H 'Content-Type: application/json' -d "$data")
  local h; for h in "$@"; do args+=(-H "$h"); done
  STATUS=$(curl "${args[@]}") || fail "curl fallo: $method $url"
  BODY=$(cat "$out")
}
# treq METHOD PATH [BODY] [header...]  (Triage, con X-API-Key)
treq() { local m="$1" p="$2" d="${3-}"; shift $(( $# >= 3 ? 3 : $# )); req "$m" "$TRIAGE_URL$p" "$d" "X-API-Key: $TRIAGE_API_KEY" "$@"; }
areq() { local m="$1" p="$2" d="${3-}"; shift $(( $# >= 3 ? 3 : $# )); req "$m" "$ASSIST_URL$p" "$d" "X-API-Key: $ASISTENTE_API_KEY" "$@"; }

assert_status() { [[ "$STATUS" == "$1" ]] || fail "status esperado $1, obtenido $STATUS. Cuerpo: ${BODY:0:300}"; }
assert_eq()     { [[ "$1" == "$2" ]] || fail "${3:-valor}: esperado '$2', obtenido '$1'"; }
# assert_jq FILTER EXPECTED : evalua jq -r sobre $BODY
assert_jq() {
  local got; got=$(jq -r "$1" <<<"$BODY" 2>/dev/null) || fail "jq invalido o cuerpo no JSON ($1): ${BODY:0:200}"
  [[ "$got" == "$2" ]] || fail "jq '$1': esperado '$2', obtenido '$got'"
}
assert_jq_true()  { jq -e "$1" <<<"$BODY" >/dev/null 2>&1 || fail "jq '$1' no se cumple. Cuerpo: ${BODY:0:300}"; }
assert_contains() { [[ "$1" == *"$2"* ]] || fail "${3:-texto}: no contiene '$2' (obtenido '${1:0:200}')"; }

# payload NOMBRE EDAD QUEJA FC SIS DIA SPO2 TEMP DOLOR GCS
payload() {
  jq -nc --arg n "$1" --argjson e "$2" --arg q "$3" --argjson fc "$4" --argjson s "$5" --argjson d "$6" \
    --argjson o "$7" --argjson t "$8" --argjson p "$9" --argjson g "${10}" \
    '{nombreCompleto:$n,edad:$e,quejaPrincipal:$q,signosVitales:{frecuenciaCardiaca:$fc,presionSistolica:$s,presionDiastolica:$d,spo2:$o,temperatura:$t,dolor:$p,gcs:$g}}'
}
# Paciente sano base (NO_URGENTE) para mutar con jq.
base_payload() { payload "Paciente Prueba E2E" 40 "Herida por cortar" 72 120 80 99 36.5 2 15; }
# Paciente CRITICO (SLA corto en el entorno E2E).
critico_payload() { payload "Paciente Critico E2E" 55 "Dolor toracico" 130 180 110 97 36.8 8 15; }

# register PAYLOAD -> ID, NIVEL (falla si no es 201)
register() {
  treq POST /triage/pacientes "$1"; assert_status 201
  ID=$(jq -r .id <<<"$BODY"); NIVEL=$(jq -r .nivel <<<"$BODY")
}
patch_estado() { treq PATCH "/triage/pacientes/$1/estado" "{\"estado\":\"$2\"}"; }
get_patient()  { treq GET "/triage/pacientes/$1"; }

# poll_until TIMEOUT_S "comando que devuelve 0 cuando se cumple"
poll_until() {
  local end=$((SECONDS + $1))
  while (( SECONDS < end )); do eval "$2" && return 0; sleep 1; done
  return 1
}

# Estado de urgencias del Asistente
urg_json()  { curl -sS --max-time 10 -H "X-API-Key: $ASISTENTE_API_KEY" "$ASSIST_URL/asistente/estado-urgencias"; }
urg_level() { urg_json | jq -r ".niveles.$1"; }
urg_total() { urg_json | jq -r ".totalActivos"; }

# Docker compose del stack E2E (definido por run.sh en $DC_ARGS)
dc() { docker compose "${DC_ARGS[@]}" "$@"; }
psql_q() { dc exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1" | tr -d '[:space:]'; }
