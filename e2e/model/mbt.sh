#!/usr/bin/env bash
# MBT del ciclo de vida del paciente (RF-02/RF-03/RN-03) contra el stack real, con bash 3.2 + curl + jq.
#
# Lee patient_model.json (estados = (estado, escalado); transiciones validas, invalidas y de tiempo), genera un caso por
# transicion (camino mas corto desde el estado inicial + la transicion = cobertura de TODAS las transiciones),
# los ejecuta en paralelo con curl y comprueba el oraculo del modelo en cada paso (HTTP, estado, escalado, alertas).
# Al final comprueba el oraculo de Kafka: el conteo activo del Asistente sube segun los pacientes que NO terminan en ALTA.
#
# Uso: mbt.sh --triage URL --assistant URL --triage-key K --assistant-key K [--sla-seconds 60] --model patient_model.json
# Salida 0 si todos los casos pasan y la cobertura de transiciones es 100 %.
set -uo pipefail

SLA=60; MARGIN=10; STABLE=6
while [[ $# -gt 0 ]]; do
  case "$1" in
    --triage) TRIAGE="$2";; --assistant) ASSIST="$2";; --triage-key) TKEY="$2";; --assistant-key) AKEY="$2";;
    --sla-seconds) SLA="$2";; --model) MODEL="$2";;
    *) echo "opcion desconocida: $1" >&2; exit 2;;
  esac; shift 2
done
for v in TRIAGE ASSIST TKEY AKEY MODEL; do [[ -n "${!v-}" ]] || { echo "falta --$v" >&2; exit 2; }; done

T="$(mktemp -d "${TMPDIR:-/tmp}/mbt.XXXXXX")"
trap 'rm -rf "$T"' EXIT

PAYLOAD='{"nombreCompleto":"Paciente MBT","edad":50,"quejaPrincipal":"Dolor toracico","signosVitales":{"frecuenciaCardiaca":130,"presionSistolica":180,"presionDiastolica":110,"spo2":97,"temperatura":36.8,"dolor":8,"gcs":15}}'

# ---- modelo ----
INITIAL=$(jq -r .initial "$MODEL")
STATES=$(jq -r '.states[].id' "$MODEL")
ACTIONS=$(jq -r '[.transitions[].action]|unique|.[]' "$MODEL")
state_estado()   { jq -r --arg s "$1" '.states[]|select(.id==$s)|.estado' "$MODEL"; }
state_escalado() { jq -r --arg s "$1" '.states[]|select(.id==$s)|.escalado' "$MODEL"; }
# tr_of FROM ACTION -> "to<TAB>http<TAB>kind"
tr_of() { jq -r --arg f "$1" --arg a "$2" '.transitions[]|select(.from==$f and .action==$a)|[.to,(.http//"-"),.kind]|@tsv' "$MODEL"; }

# Consistencia: cada estado define todas las acciones
for s in $STATES; do for a in $ACTIONS; do
  [[ -n "$(tr_of "$s" "$a")" ]] || { echo "MODELO INCOMPLETO: $s sin $a"; exit 2; }
done; done

# ---- plan: BFS sobre transiciones que cambian de estado (camino minimo a cada estado) ----
echo "" > "$T/path.$INITIAL"
queue="$INITIAL"
while [[ -n "$queue" ]]; do
  s="${queue%% *}"; rest="${queue#"$s"}"; queue="${rest# }"
  for a in $ACTIONS; do
    IFS=$'\t' read -r to _ _ <<<"$(tr_of "$s" "$a")"
    if [[ "$to" != "$s" && ! -f "$T/path.$to" ]]; then
      echo "$(cat "$T/path.$s") $a" | sed 's/^ *//' > "$T/path.$to"
      queue="$queue $to"; queue="${queue# }"
    fi
  done
done
# un caso por transicion
: > "$T/cases"
jq -r '.transitions[]|[.from,.action,.to]|@tsv' "$MODEL" > "$T/transitions.tsv"
NCASES=$(wc -l < "$T/transitions.tsv" | tr -d ' ')
NSTATES=$(echo "$STATES" | wc -l | tr -d ' ')
echo "MBT: $NSTATES estados, $NCASES transiciones -> $NCASES casos"

# ---- http ----
# treq METHOD PATH [BODY] -> RS (status), RB (cuerpo). Archivo temporal propio por llamada (seguro en paralelo).
treq() {
  local out args
  args=(-sS --max-time 40 -X "$1" -w '\n%{http_code}' -H "X-API-Key: $TKEY" "$TRIAGE$2")
  [[ -n "${3-}" ]] && args+=(-H 'Content-Type: application/json' -d "$3")
  out=$(curl "${args[@]}") || { RS=000; RB=""; return 0; }
  RS="${out##*$'\n'}"; RB="${out%$'\n'*}"
}
asist_total() {
  local out
  out=$(curl -sS --max-time 10 -w '\n%{http_code}' -H "X-API-Key: $AKEY" "$ASSIST/asistente/estado-urgencias") || return 1
  [[ "${out##*$'\n'}" == 200 ]] || return 1
  jq -r .totalActivos <<<"${out%$'\n'*}"
}

# ---- ejecucion de un caso (corre en un subshell en segundo plano) ----
cfail() { echo "$*" > "$T/res.$IDX"; exit 1; }
alert_count() { # PID -> numero de alertas del paciente (pagina por cursor)
  local pid="$1" cursor=0 n=0 len
  while :; do
    treq GET "/triage/alertas?despuesDe=$cursor&limite=200"
    [[ "$RS" == 200 ]] || cfail "GET alertas -> $RS"
    n=$((n + $(jq --arg p "$pid" '[.[]|select(.patientId==$p)]|length' <<<"$RB")))
    len=$(jq length <<<"$RB")
    (( len < 200 )) && { echo "$n"; return; }
    cursor=$(jq '.[-1].deliveryCursor' <<<"$RB")
  done
}
run_step() { # PID T_REG CURRENT ACTION -> imprime estado siguiente
  local pid="$1" t_reg="$2" cur="$3" action="$4" to http kind exp_estado exp_esc wake end estado wire got
  echo "$cur|$action" >> "$T/edges.$IDX"
  IFS=$'\t' read -r to http kind <<<"$(tr_of "$cur" "$action")"
  exp_estado=$(state_estado "$to"); exp_esc=$(state_escalado "$to")
  if [[ "$action" == WAIT_SLA ]]; then
    wake=$((t_reg + SLA + MARGIN)); now=$(date +%s)
    (( wake > now )) && sleep $((wake - now))
    if [[ "$to" != "$cur" ]]; then   # REGISTRADO sin escalar -> debe escalar
      end=$(( $(date +%s) + 30 ))
      while (( $(date +%s) < end )); do
        treq GET "/triage/pacientes/$pid"
        [[ "$RS" == 200 && "$(jq -r .flags.escalado <<<"$RB")" == true ]] && break
        sleep 1
      done
    else
      sleep "$STABLE"                # no debe cambiar nada (idempotencia o nunca escalar)
    fi
  else
    estado="${action#PATCH:}"; wire="$estado"; [[ "$estado" == DESCONOCIDO ]] && wire=FOO_NO_EXISTE
    treq PATCH "/triage/pacientes/$pid/estado" "{\"estado\":\"$wire\"}"
    [[ "$RS" == "$http" ]] || cfail "$action desde $cur: HTTP $RS esperado $http ($RB)"
    if [[ "$RS" == 200 ]]; then
      got=$(jq -r .estado <<<"$RB"); [[ "$got" == "$exp_estado" ]] || cfail "$action: estado $got != $exp_estado"
    fi
    if [[ "$RS" == 409 ]]; then
      got=$(jq -r .estadoActual <<<"$RB"); [[ "$got" == "$(state_estado "$cur")" ]] || cfail "409 estadoActual: $RB"
    fi
  fi
  # Oraculo: GET refleja estado y escalado del modelo
  treq GET "/triage/pacientes/$pid"
  [[ "$RS" == 200 ]] || cfail "GET -> $RS"
  got=$(jq -r .estado <<<"$RB"); [[ "$got" == "$exp_estado" ]] || cfail "tras $action: estado $got != modelo $exp_estado"
  got=$(jq -r .flags.escalado <<<"$RB"); [[ "$got" == "$exp_esc" ]] || cfail "tras $action: escalado $got != modelo $exp_esc"
  if [[ "$action" == WAIT_SLA ]]; then
    got=$(alert_count "$pid")
    if [[ "$exp_esc" == true ]]; then [[ "$got" == 1 ]] || cfail "alertas del paciente = $got, esperado 1"
    else [[ "$got" == 0 ]] || cfail "alertas del paciente = $got, esperado 0"; fi
  fi
  echo "$to"
}
run_case() { # IDX FROM ACTION
  IDX="$1"; local from="$2" edge="$3" pid t_reg cur a
  treq POST /triage/pacientes "$PAYLOAD"
  [[ "$RS" == 201 && "$(jq -r .nivel <<<"$RB")" == CRITICO ]] || cfail "registro -> $RS $RB"
  pid=$(jq -r .id <<<"$RB"); t_reg=$(date +%s); cur="$INITIAL"
  for a in $(cat "$T/path.$from") $edge; do
    cur=$(run_step "$pid" "$t_reg" "$cur" "$a") || exit 1
    [[ -f "$T/res.$IDX" ]] && exit 1
  done
  echo "$cur" > "$T/final.$IDX"
  echo OK > "$T/res.$IDX"
}


sleep 2
BASE=$(asist_total) || BASE=""

i=0
while IFS=$'\t' read -r from action to; do
  i=$((i + 1))
  echo "$from--$action-->$to" > "$T/name.$i"
  ( run_case "$i" "$from" "$action" ) &
done < "$T/transitions.tsv"
wait

# Caso global: paciente inexistente -> 404
treq PATCH /triage/pacientes/00000000-0000-4000-8000-000000000000/estado '{"estado":"ALTA"}'
NF_MSG=""; [[ "$RS" == 404 ]] || NF_MSG="HTTP $RS esperado 404"

# Oraculo Kafka/Asistente: activos esperados = casos cuyo estado final del modelo no es ALTA
EXPECTED=0; FAILED=0; PASSED=0
for ((n = 1; n <= NCASES; n++)); do
  if [[ "$(cat "$T/res.$n" 2>/dev/null)" == OK ]]; then
    PASSED=$((PASSED + 1))
    [[ "$(state_estado "$(cat "$T/final.$n")")" != ALTA ]] && EXPECTED=$((EXPECTED + 1))
  else FAILED=$((FAILED + 1)); fi
done
KAFKA_MSG=""
if [[ -n "$BASE" ]]; then
  end=$(( $(date +%s) + 40 )); got=""
  while (( $(date +%s) < end )); do
    got=$(asist_total) || got=""
    [[ "$got" == "$((BASE + EXPECTED))" ]] && break
    sleep 1
  done
  [[ "$got" == "$((BASE + EXPECTED))" ]] || KAFKA_MSG="totalActivos del Asistente=$got, esperado $BASE+$EXPECTED"
else KAFKA_MSG="no se pudo leer estado-urgencias"; fi

echo
for ((n = 1; n <= NCASES; n++)); do
  r=$(cat "$T/res.$n" 2>/dev/null || echo "sin resultado")
  if [[ "$r" == OK ]]; then echo "  PASS  $(cat "$T/name.$n")"
  else echo "  FAIL  $(cat "$T/name.$n")"; echo "        $r"; fi
done
if [[ -z "$NF_MSG" ]]; then echo "  PASS  PATCH id inexistente -> 404"; else echo "  FAIL  PATCH id inexistente -> 404 ($NF_MSG)"; fi
if [[ -z "$KAFKA_MSG" ]]; then echo "  PASS  oraculo Kafka: activos esperados +$EXPECTED"; else echo "  FAIL  oraculo Kafka: activos esperados +$EXPECTED ($KAFKA_MSG)"; fi

# Cobertura de transiciones: aristas ejecutadas (camino + transicion) vs aristas del modelo
jq -r '.transitions[]|"\(.from)|\(.action)"' "$MODEL" | sort -u > "$T/model_edges"
cat "$T"/edges.* 2>/dev/null | sort -u > "$T/run_edges"
TOTAL=$(wc -l < "$T/model_edges" | tr -d ' ')
COV=$(comm -12 "$T/model_edges" "$T/run_edges" | wc -l | tr -d ' ')
NV=$(jq '[.transitions[]|select(.kind=="valida")]|length' "$MODEL")
NI=$(jq '[.transitions[]|select(.kind=="invalida")]|length' "$MODEL")
NT=$(jq '[.transitions[]|select(.kind=="tiempo")]|length' "$MODEL")
echo
echo "Cobertura de transiciones: $COV/$TOTAL (validas=$NV, invalidas=$NI, tiempo=$NT)"
BAD=$FAILED; [[ -n "$NF_MSG" ]] && BAD=$((BAD + 1)); [[ -n "$KAFKA_MSG" ]] && BAD=$((BAD + 1))
echo "MBT casos de transicion: $PASSED/$NCASES OK, $FAILED fail (mas 404 y oraculo Kafka); fallos totales: $BAD"
[[ $BAD -eq 0 && $COV -eq $TOTAL ]]
