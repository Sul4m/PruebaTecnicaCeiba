#!/usr/bin/env bash
# Definicion de pasos/escenarios BDD. Cada funcion sc_* implementa un escenario @Sxx de features/*.feature.
# Se carga con `source` desde run.sh (usa helpers de lib/lib.sh).

UUID_RE='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
ISO_RE='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$'
# SLA esperado por nivel en el entorno E2E: CRITICO se acorta a 1 min via compose.e2e.yml; el resto es RN-02.
sla_of() { case "$1" in CRITICO) echo 1;; URGENTE) echo 10;; URGENTE_2) echo 30;; NO_URGENTE) echo 60;; NO_URGENTE_2) echo 120;; esac; }

# Todo @Sxx de los .feature debe tener funcion registrada en run.sh y viceversa.
check_feature_tags() {
  local f tags run_tags missing=0
  tags=$(cat "$E2E_DIR"/features/*.feature | grep -oE '@S[0-9]{2}' | tr -d '@' | sort -u)
  run_tags=$(grep -oE 'run_scenario S[0-9]{2}' "$E2E_DIR/run.sh" | awk '{print $2}' | sort -u)
  if [[ "$tags" != "$run_tags" ]]; then
    echo "Los tags @Sxx de features/ y run.sh difieren:" >&2
    diff <(echo "$tags") <(echo "$run_tags") >&2; return 1
  fi
}

# ---------------------------------------------------------------- preparacion de escalamiento
prepare_escalation() {
  echo "== Preparando escalamiento: pacientes CRITICO (SLA 1 min) y escucha SSE =="
  : > "$WORK/sse_live.txt"
  curl -sN --max-time 200 -H "X-API-Key: $TRIAGE_API_KEY" -H 'Accept: text/event-stream' \
    "$TRIAGE_URL/triage/alertas/stream" > "$WORK/sse_live.txt" 2>/dev/null &
  sleep 1
  ( register "$(critico_payload)"; echo "ESC1=$ID" > "$WORK/esc.env"; echo "ESC_T0=$(date +%s)" >> "$WORK/esc.env"
    register "$(critico_payload)"; patch_estado "$ID" EN_CONSULTA; [[ "$STATUS" == 200 ]] || exit 1; echo "ESC2=$ID" >> "$WORK/esc.env"
    register "$(critico_payload)"; patch_estado "$ID" ALTA; [[ "$STATUS" == 200 ]] || exit 1; echo "ESC3=$ID" >> "$WORK/esc.env"
  ) || { echo "No se pudo preparar el escalamiento" >&2; exit 1; }
  # shellcheck disable=SC1091
  source "$WORK/esc.env"
  echo "   ESC1=$ESC1 (REGISTRADO), ESC2=$ESC2 (EN_CONSULTA), ESC3=$ESC3 (ALTA)"
}
load_esc() { source "$WORK/esc.env"; }
wait_escalated() { # espera a que ESC1 quede escalado (SLA 1 min + intervalo 2 s)
  load_esc
  poll_until 150 '[[ "$(get_patient "$ESC1"; jq -r .flags.escalado <<<"$BODY")" == "true" ]]' \
    || fail "ESC1 no fue escalado en 150 s"
}

# ---------------------------------------------------------------- S01..S05 registro y clasificacion
sc_e1_e6() {
  local rows=(
    "E1|130|180|110|97|36.8|8|15|Dolor torácico|CRITICO"
    "E2|95|150|95|91|37.0|4|15|Dificultad para respirar|URGENTE"
    "E3|88|130|85|98|38.2|6|15|Fiebre|URGENTE_2"
    "E4|72|120|80|99|36.5|2|15|Herida por cortar|NO_URGENTE"
    "E5|70|115|75|99|36.4|0|15|Renovación de recetario|NO_URGENTE_2"
    "E6|35|80|50|85|35.9|0|6|Inconsciente|CRITICO")
  local r c fc sis dia spo2 t dol gcs q exp
  for r in "${rows[@]}"; do
    IFS='|' read -r c fc sis dia spo2 t dol gcs q exp <<<"$r"
    dado "el caso $c ($q) con FC=$fc sis=$sis dia=$dia SpO2=$spo2 T=$t dolor=$dol GCS=$gcs"
    cuando "se registra con POST /triage/pacientes"
    treq POST /triage/pacientes "$(payload "Paciente $c" 45 "$q" "$fc" "$sis" "$dia" "$spo2" "$t" "$dol" "$gcs")"
    entonces "responde 201 con nivel $exp"
    assert_status 201; assert_jq .nivel "$exp"; assert_jq .estado REGISTRADO
  done
}

_classify() { # "mutacion jq" nivel_esperado [queja]
  local pl; pl=$(base_payload | jq -c "$1")
  [[ -n "${3-}" ]] && pl=$(jq -c --arg q "$3" '.quejaPrincipal=$q' <<<"$pl")
  treq POST /triage/pacientes "$pl"; assert_status 201
  assert_eq "$(jq -r .nivel <<<"$BODY")" "$2" "nivel para [$1]"
  echo "      ok: [$1] -> $2"
}
sc_boundaries() {
  dado "signos sanos de NO_URGENTE y una variable en la frontera"
  cuando "se registra cada paciente"
  entonces "el nivel respeta limites inclusivos/exclusivos de RN-01"
  local V='.signosVitales'
  _classify "$V.frecuenciaCardiaca=40" NO_URGENTE;  _classify "$V.frecuenciaCardiaca=39" CRITICO
  _classify "$V.frecuenciaCardiaca=120" URGENTE_2;  _classify "$V.frecuenciaCardiaca=121" CRITICO
  _classify "$V.frecuenciaCardiaca=90" URGENTE_2;   _classify "$V.frecuenciaCardiaca=89" NO_URGENTE
  _classify "$V.spo2=89" CRITICO; _classify "$V.spo2=90" URGENTE; _classify "$V.spo2=93" URGENTE; _classify "$V.spo2=94" NO_URGENTE
  _classify "$V.presionDiastolica=50|$V.presionSistolica=89" CRITICO
  _classify "$V.presionDiastolica=50|$V.presionSistolica=90" URGENTE
  _classify "$V.presionDiastolica=50|$V.presionSistolica=100" URGENTE
  _classify "$V.presionDiastolica=50|$V.presionSistolica=101" NO_URGENTE
  _classify "$V.temperatura=38.5" URGENTE;   _classify "$V.temperatura=38.4" URGENTE_2
  _classify "$V.temperatura=38.0" URGENTE_2; _classify "$V.temperatura=37.9" NO_URGENTE
  _classify "$V.gcs=8" CRITICO; _classify "$V.gcs=9" URGENTE; _classify "$V.gcs=13" URGENTE; _classify "$V.gcs=14" NO_URGENTE
  _classify "$V.dolor=7" URGENTE_2; _classify "$V.dolor=6" NO_URGENTE
  y "si se cumplen varios niveles prevalece el mas grave"
  _classify "$V.spo2=91|$V.dolor=8|$V.frecuenciaCardiaca=100" URGENTE
  _classify "$V.spo2=85|$V.temperatura=38.2" CRITICO
}

sc_admin() {
  dado "signos vitales sanos"
  local q
  for q in "documentos" "recolección de resultados" "renovación de recetario" "vacunación" "seguimiento" "Vacunación"; do
    cuando "la queja principal es '$q'"; _classify '.' NO_URGENTE_2 "$q"
  done
  entonces "una queja no administrativa queda en NO_URGENTE"; _classify '.' NO_URGENTE "Dolor de cabeza"
  y "una queja administrativa NO rebaja un nivel de mayor gravedad"
  _classify '.signosVitales.frecuenciaCardiaca=130' CRITICO "renovación de recetario"
  _classify '.signosVitales.spo2=92' URGENTE "vacunación"
  _classify '.signosVitales.temperatura=38.1' URGENTE_2 "documentos"
}

sc_register_response() {
  local lvl pl mut
  for lvl in CRITICO URGENTE URGENTE_2 NO_URGENTE NO_URGENTE_2; do
    case "$lvl" in
      CRITICO) mut='.signosVitales.frecuenciaCardiaca=130';; URGENTE) mut='.signosVitales.spo2=92';;
      URGENTE_2) mut='.signosVitales.dolor=8';; NO_URGENTE_2) mut='.quejaPrincipal="seguimiento"';; *) mut='.';;
    esac
    pl=$(base_payload | jq -c "$mut")
    dado "un paciente de nivel $lvl"; cuando "se registra"
    treq POST /triage/pacientes "$pl"; assert_status 201
    entonces "la respuesta trae id, nivel, slaMinutos=$(sla_of "$lvl"), estado REGISTRADO y fechaRegistro ISO 8601"
    assert_jq .nivel "$lvl"; assert_jq .slaMinutos "$(sla_of "$lvl")"; assert_jq .estado REGISTRADO
    # jq (Oniguruma) en vez de [[ =~ ]]: bash 3.2 de macOS no soporta bien los intervalos {n}
    jq -e --arg u "$UUID_RE" '.id|test($u)' <<<"$BODY" >/dev/null || fail "id no es UUID"
    jq -e --arg i "$ISO_RE" '.fechaRegistro|test($i)' <<<"$BODY" >/dev/null || fail "fechaRegistro no es ISO 8601"
  done
}

sc_get_patient() {
  dado "un paciente registrado"; register "$(critico_payload)"
  cuando "se consulta GET /triage/pacientes/{id}"; get_patient "$ID"; assert_status 200
  entonces "trae datos completos, estado, flags.escalado=false y tiempo en el estado actual"
  assert_jq .id "$ID"; assert_jq .nombreCompleto "Paciente Critico E2E"; assert_jq .estado REGISTRADO
  assert_jq .flags.escalado false; assert_jq .signosVitales.spo2 97
  assert_jq_true '.minutosEnEstadoActual >= 0 and (.fechaCambioEstado|type=="string")'
  cuando "el id no existe"; get_patient "00000000-0000-4000-8000-000000000000"
  entonces "responde 404"; assert_status 404
  cuando "el id no es un UUID"; get_patient "no-es-uuid"
  entonces "responde 400 con campo"; assert_status 400; assert_jq_true '.campo != null'
}

# ---------------------------------------------------------------- S06..S08 validaciones y auth
sc_validations() {
  local V='.signosVitales' c mut campo
  local cases=(
    "$V.frecuenciaCardiaca=19|frecuenciaCardiaca" "$V.frecuenciaCardiaca=251|frecuenciaCardiaca"
    "$V.presionSistolica=59|presionSistolica" "$V.presionSistolica=261|presionSistolica"
    "$V.presionDiastolica=29|presionDiastolica" "$V.presionDiastolica=161|presionDiastolica"
    "$V.presionDiastolica=120|presionDiastolica"
    "$V.presionDiastolica=130|presionDiastolica"
    "$V.spo2=69|spo2" "$V.spo2=101|spo2"
    "$V.temperatura=33.9|temperatura" "$V.temperatura=42.1|temperatura"
    "$V.dolor=-1|dolor" "$V.dolor=11|dolor"
    "$V.gcs=2|gcs" "$V.gcs=16|gcs"
    ".edad=-1|edad" ".edad=121|edad"
    '.nombreCompleto="ab"|nombreCompleto'
    ".nombreCompleto=(\"x\"*101)|nombreCompleto"
    '.quejaPrincipal=""|quejaPrincipal'
    "del($V.spo2)|spo2" "del(.signosVitales)|signosVitales" 'del(.nombreCompleto)|nombreCompleto'
    '.edad="abc"|edad')
  dado "un paciente valido al que se le invalida un campo"
  for c in "${cases[@]}"; do
    mut="${c%|*}"; campo="${c##*|}"
    cuando "se envia con [$mut]"
    treq POST /triage/pacientes "$(base_payload | jq -c "$mut")"
    entonces "responde 400 y el campo '$campo' queda identificado"
    assert_status 400
    assert_contains "$(jq -r '.campo // ""' <<<"$BODY")" "$campo" "campo (detail: $(jq -r .detail <<<"$BODY"))"
  done
  cuando "el cuerpo es JSON mal formado"; treq POST /triage/pacientes '{no-json'
  entonces "responde 400"; assert_status 400
}

sc_valid_limits() {
  local V='.signosVitales' m
  local muts=("$V.frecuenciaCardiaca=20" "$V.frecuenciaCardiaca=250" "$V.presionSistolica=60|$V.presionDiastolica=30"
    "$V.presionSistolica=260|$V.presionDiastolica=160" "$V.presionDiastolica=119" "$V.spo2=70" "$V.spo2=100"
    "$V.temperatura=34.0" "$V.temperatura=42.0" "$V.dolor=0" "$V.dolor=10" "$V.gcs=3" "$V.gcs=15"
    ".edad=0" ".edad=120" '.nombreCompleto="abc"' ".nombreCompleto=(\"x\"*100)")
  dado "valores exactamente en el limite del rango valido"
  for m in "${muts[@]}"; do
    cuando "se registra con [$m]"; treq POST /triage/pacientes "$(base_payload | jq -c "$m")"
    entonces "se acepta con 201"; assert_status 201
  done
}

_body_for() { case "$1" in POST) base_payload;; PATCH) echo '{"estado":"ALTA"}';; *) echo "";; esac; }
sc_auth() {
  register "$(base_payload)"; local pid="$ID" bad_key="X-API-Key: clave-incorrecta" p
  local calls=("POST /triage/pacientes" "GET /triage/pacientes/$pid" "PATCH /triage/pacientes/$pid/estado"
    "GET /triage/reportes/operacion?desde=2020-01-01T00:00:00Z&hasta=2030-01-01T00:00:00Z" "GET /triage/alertas")
  for p in "${calls[@]}"; do
    cuando "se invoca '$p' sin X-API-Key"
    req "${p%% *}" "$TRIAGE_URL${p#* }" "$(_body_for "${p%% *}")"; entonces "responde 401 con cuerpo vacio"
    assert_status 401; assert_eq "$BODY" "" "cuerpo"
    cuando "se invoca '$p' con clave incorrecta"
    req "${p%% *}" "$TRIAGE_URL${p#* }" "$(_body_for "${p%% *}")" "$bad_key"; entonces "responde 401"; assert_status 401
  done
  cuando "se abre el stream SSE sin clave"; req GET "$TRIAGE_URL/triage/alertas/stream" ""; entonces "responde 401"; assert_status 401
  cuando "el Asistente recibe POST /asistente/consulta sin clave"
  req POST "$ASSIST_URL/asistente/consulta" '{"pregunta":"hola"}'; entonces "responde 401"; assert_status 401
  cuando "el Asistente recibe GET estado-urgencias sin clave o con clave de Triage"
  req GET "$ASSIST_URL/asistente/estado-urgencias" ""; assert_status 401
  req GET "$ASSIST_URL/asistente/estado-urgencias" "" "X-API-Key: $TRIAGE_API_KEY"
  entonces "responde 401 (las claves no son intercambiables)"; assert_status 401
  cuando "se consulta la salud sin clave"; req GET "$TRIAGE_URL/actuator/health/readiness" ""; assert_status 200
  req GET "$ASSIST_URL/actuator/health/readiness" ""; entonces "responde 200 (sondas publicas)"; assert_status 200
}

# ---------------------------------------------------------------- S09..S11 estados
_audit_count() { psql_q "select count(*) from audit_event where patient_id='$1'"; }
sc_valid_transitions() {
  dado "un paciente REGISTRADO"; register "$(base_payload)"; local a="$ID"
  cuando "pasa a EN_CONSULTA"; patch_estado "$a" EN_CONSULTA
  entonces "responde 200 con EN_CONSULTA"; assert_status 200; assert_jq .estado EN_CONSULTA
  cuando "pasa a ALTA"; patch_estado "$a" ALTA; entonces "responde 200 con ALTA"; assert_status 200; assert_jq .estado ALTA
  y "el GET refleja ALTA y hay 3 auditorias (registro, consulta, alta)"
  get_patient "$a"; assert_jq .estado ALTA; assert_eq "$(_audit_count "$a")" 3 "auditorias"
  assert_eq "$(psql_q "select string_agg(event_type, ',' order by occurred_at) from audit_event where patient_id='$a'")" \
    "paciente_registrado,paciente_en_consulta,paciente_alta" "tipos de auditoria"
  dado "otro paciente REGISTRADO"; register "$(base_payload)"; local b="$ID"
  cuando "recibe alta directa"; patch_estado "$b" ALTA
  entonces "responde 200 con ALTA y 2 auditorias"; assert_status 200; assert_jq .estado ALTA; assert_eq "$(_audit_count "$b")" 2 "auditorias"
}

sc_invalid_transitions() {
  local s from to n0 n1
  local pairs=("EN_CONSULTA:REGISTRADO" "EN_CONSULTA:EN_CONSULTA" "ALTA:REGISTRADO" "ALTA:EN_CONSULTA" "ALTA:ALTA" "REGISTRADO:REGISTRADO")
  for s in "${pairs[@]}"; do
    from="${s%%:*}"; to="${s##*:}"
    dado "un paciente en $from"; register "$(base_payload)"
    [[ $from == EN_CONSULTA ]] && { patch_estado "$ID" EN_CONSULTA; assert_status 200; }
    [[ $from == ALTA ]] && { patch_estado "$ID" ALTA; assert_status 200; }
    n0=$(_audit_count "$ID")
    cuando "se solicita $to"; patch_estado "$ID" "$to"
    entonces "responde 409 con estado actual y solicitado, sin cambiar el paciente ni auditar"
    assert_status 409; assert_jq .estadoActual "$from"; assert_jq .estadoSolicitado "$to"
    assert_jq_true '(.detail|length) > 0'
    get_patient "$ID"; assert_jq .estado "$from"
    n1=$(_audit_count "$ID"); assert_eq "$n1" "$n0" "auditorias"
  done
}

sc_patch_errors() {
  register "$(base_payload)"; local id="$ID"
  cuando "el estado es desconocido"; patch_estado "$id" "FOO"; entonces "responde 400"; assert_status 400
  cuando "el cuerpo no trae estado"; treq PATCH "/triage/pacientes/$id/estado" '{}'; entonces "responde 400"; assert_status 400
  cuando "el paciente no existe"; patch_estado "00000000-0000-4000-8000-000000000000" ALTA
  entonces "responde 404"; assert_status 404
  cuando "el id no es UUID"; patch_estado "xyz" ALTA; entonces "responde 400"; assert_status 400
  y "el paciente original no cambio"; get_patient "$id"; assert_jq .estado REGISTRADO
}

# ---------------------------------------------------------------- S12..S13 reportes
_report_sum() { # imprime "total CRITICO URGENTE NO_URGENTE" sumando filas
  treq GET "/triage/reportes/operacion?desde=$(date -u -v-2d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '-2 days' +%Y-%m-%dT%H:%M:%SZ)&hasta=$(date -u -v+2d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+2 days' +%Y-%m-%dT%H:%M:%SZ)"
  assert_status 200
  jq -r '[ (map(.totalRegistrados)|add // 0), (map(.porNivel.CRITICO // 0)|add // 0), (map(.porNivel.URGENTE // 0)|add // 0), (map(.porNivel.NO_URGENTE // 0)|add // 0) ] | @tsv' <<<"$BODY"
}
sc_report() {
  local t0 c0 u0 n0 t1 c1 u1 n1 id
  read -r t0 c0 u0 n0 <<<"$(_report_sum)"
  dado "3 pacientes nuevos (CRITICO, URGENTE, NO_URGENTE), uno de ellos pasa a EN_CONSULTA"
  register "$(critico_payload)"; id="$ID"
  register "$(base_payload | jq -c '.signosVitales.spo2=92')"
  register "$(base_payload)"
  patch_estado "$id" EN_CONSULTA; assert_status 200
  cuando "se consulta el reporte por hora"
  read -r t1 c1 u1 n1 <<<"$(_report_sum)"
  entonces "el total sube 3 y los conteos por nivel suben 1 cada uno"
  assert_eq "$((t1 - t0))" 3 "delta total"; assert_eq "$((c1 - c0))" 1 "delta CRITICO"
  assert_eq "$((u1 - u0))" 1 "delta URGENTE"; assert_eq "$((n1 - n0))" 1 "delta NO_URGENTE"
  y "cada fila trae hora ISO, porNivel, promedio y p95"
  _report_sum >/dev/null
  assert_jq_true 'all(.[]; has("hora") and has("totalRegistrados") and has("porNivel") and has("promedioMinutosHastaConsulta") and has("p95MinutosHastaConsulta"))'
  assert_jq_true 'any(.[]; .promedioMinutosHastaConsulta != null and .p95MinutosHastaConsulta != null)'
  y "una ventana sin pacientes devuelve lista vacia"
  treq GET "/triage/reportes/operacion?desde=2001-01-01T00:00:00Z&hasta=2001-01-02T00:00:00Z"; assert_status 200; assert_jq length 0
  y "acepta offsets distintos de UTC"
  treq GET "/triage/reportes/operacion?desde=2001-01-01T00:00:00-05:00&hasta=2001-01-02T00:00:00-05:00"; assert_status 200
}
sc_report_invalid() {
  local q
  for q in "hasta=2030-01-01T00:00:00Z:desde" "desde=2020-01-01T00:00:00Z:hasta" "desde=ayer&hasta=2030-01-01T00:00:00Z:desde" \
           "desde=2030-01-01T00:00:00Z&hasta=2020-01-01T00:00:00Z:desde" "desde=2020-01-01T00:00:00Z&hasta=2020-01-01T00:00:00Z:desde"; do
    cuando "se consulta con '${q%:*}'"; treq GET "/triage/reportes/operacion?${q%:*}"
    entonces "responde 400 con campo '${q##*:}'"; assert_status 400; assert_jq .campo "${q##*:}"
  done
}

# ---------------------------------------------------------------- S19..S20 Kafka
sc_kafka_counts() {
  local lvl=URGENTE b t0 b2
  dado "el conteo del Asistente estabilizado (igual a los pacientes no-ALTA de la base de Triage)"
  poll_until 40 '[[ "$(urg_total)" == "$(psql_q "select count(*) from patient where status<>'"'"'ALTA'"'"'")" ]]' \
    || fail "la proyeccion no converge con la base (asistente=$(urg_total))"
  b=$(urg_level $lvl); t0=$(urg_total)
  cuando "se registra un paciente URGENTE en Triage"
  register "$(base_payload | jq -c '.signosVitales.spo2=92')"; local id="$ID"
  entonces "el Asistente (via Kafka, sin llamar a Triage) sube $lvl y el total en 1"
  poll_until 30 "[[ \"\$(urg_level $lvl)\" == \"$((b + 1))\" ]]" || fail "el conteo no subio (antes=$b, ahora=$(urg_level $lvl))"
  assert_eq "$(urg_total)" "$((t0 + 1))" "totalActivos"
  cuando "pasa a EN_CONSULTA"; patch_estado "$id" EN_CONSULTA; assert_status 200
  entonces "sigue contando como activo"; sleep 3; assert_eq "$(urg_level $lvl)" "$((b + 1))" "conteo en consulta"
  cuando "se da de alta"; patch_estado "$id" ALTA; assert_status 200
  entonces "el conteo baja en 1"
  poll_until 30 "[[ \"\$(urg_level $lvl)\" == \"$b\" ]]" || fail "el conteo no bajo (esperado $b, ahora=$(urg_level $lvl))"
  assert_eq "$(urg_total)" "$t0" "totalActivos tras alta"
  dado "un paciente NO_URGENTE_2 con alta directa"; b2=$(urg_level NO_URGENTE_2)
  register "$(base_payload | jq -c '.quejaPrincipal="seguimiento"')"
  poll_until 30 "[[ \"\$(urg_level NO_URGENTE_2)\" == \"$((b2 + 1))\" ]]" || fail "NO_URGENTE_2 no subio"
  patch_estado "$ID" ALTA; assert_status 200
  poll_until 30 "[[ \"\$(urg_level NO_URGENTE_2)\" == \"$b2\" ]]" || fail "NO_URGENTE_2 no bajo tras alta directa"
  y "la respuesta siempre trae los 5 niveles"
  BODY=$(urg_json); assert_jq_true '(.niveles|keys|length)==5 and (.totalActivos|type=="number")'
}

kafka_lines() { # imprime "clave|json" de triaje.eventos
  dc exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic triaje.eventos \
    --from-beginning --timeout-ms 12000 --property print.key=true --property key.separator='|' 2>/dev/null
}
sc_kafka_events() {
  dado "un paciente que se registra, entra a consulta y recibe alta"
  register "$(base_payload)"; local id="$ID"; patch_estado "$id" EN_CONSULTA; patch_estado "$id" ALTA
  sleep 5
  cuando "se lee el topic triaje.eventos"
  local lines; lines=$(kafka_lines | grep "^$id|" || true)
  entonces "hay 3 eventos con clave patientId, tipos y versiones en orden"
  assert_eq "$(wc -l <<<"$lines" | tr -d ' ')" 3 "eventos del paciente"
  assert_eq "$(cut -d'|' -f2- <<<"$lines" | jq -r .eventType | paste -sd, -)" "paciente_registrado,paciente_en_consulta,paciente_alta" "tipos"
  assert_eq "$(cut -d'|' -f2- <<<"$lines" | jq -r .aggregateVersion | paste -sd, -)" "1,2,3" "versiones"
  y "el envelope es v1 y NO lleva nombre, edad, queja ni signos vitales"
  cut -d'|' -f2- <<<"$lines" | jq -e -s 'all(.[]; .schemaVersion==1 and .aggregateId=="'"$id"'" and (.payload|keys|sort)==["escalated","estado","nivel","patientId"])' >/dev/null \
    || fail "envelope inesperado: $lines"
  assert_eq "$(grep -c 'Paciente Prueba E2E' <<<"$lines" || true)" 0 "PII en el evento"
}

# ---------------------------------------------------------------- S14..S18 escalamiento, alertas, SSE
sc_escalation_once() {
  load_esc
  dado "un paciente CRITICO (SLA 1 min) que sigue REGISTRADO"
  cuando "transcurre el SLA y corre el proceso periodico"; wait_escalated
  entonces "el flag escalado queda true y el estado sigue REGISTRADO"
  get_patient "$ESC1"; assert_jq .flags.escalado true; assert_jq .estado REGISTRADO
  y "hay exactamente una auditoria paciente_escalado"
  assert_eq "$(psql_q "select count(*) from audit_event where patient_id='$ESC1' and event_type='paciente_escalado'")" 1 "auditorias de escalamiento"
  y "tras varias pasadas mas del proceso (intervalo 2 s) no se duplica auditoria, alerta ni evento"
  sleep 8
  assert_eq "$(psql_q "select count(*) from audit_event where patient_id='$ESC1' and event_type='paciente_escalado'")" 1 "auditorias tras reintento"
  assert_eq "$(psql_q "select count(*) from outbox_event where aggregate_id='$ESC1' and event_type='paciente_escalado'")" 1 "outbox escalado"
  assert_eq "$(psql_q "select count(*) from medical_alert where patient_id='$ESC1'")" 1 "alertas persistidas"
  y "Kafka recibio un solo paciente_escalado para ese paciente"
  assert_eq "$(kafka_lines | grep "^$ESC1|" | grep -c paciente_escalado || true)" 1 "eventos paciente_escalado en Kafka"
  cuando "pasa despues a EN_CONSULTA"; patch_estado "$ESC1" EN_CONSULTA; assert_status 200
  entonces "el flag escalado se conserva"; assert_jq .flags.escalado true
}

sc_escalation_never() {
  load_esc; wait_escalated
  dado "pacientes CRITICO que entraron a consulta / recibieron alta antes del SLA"
  cuando "el SLA ya vencio y el proceso periodico ya corrio (ESC1 si fue escalado)"
  entonces "ESC2 (EN_CONSULTA) y ESC3 (ALTA) nunca se escalan"
  get_patient "$ESC2"; assert_jq .flags.escalado false; assert_jq .estado EN_CONSULTA
  get_patient "$ESC3"; assert_jq .flags.escalado false; assert_jq .estado ALTA
  assert_eq "$(psql_q "select count(*) from audit_event where patient_id in ('$ESC2','$ESC3') and event_type='paciente_escalado'")" 0 "auditorias de escalamiento"
  assert_eq "$(psql_q "select count(*) from medical_alert where patient_id in ('$ESC2','$ESC3')")" 0 "alertas"
}

sc_alerts() {
  load_esc; wait_escalated
  dado "ESC1 escalado"; cuando "se consulta GET /triage/alertas"; treq GET /triage/alertas; assert_status 200
  entonces "la lista contiene la alerta de ESC1 con exactamente 5 claves y sin datos clinicos"
  assert_jq_true "[.[]|select(.patientId==\"$ESC1\")]|length==1"
  assert_jq_true "([.[]|select(.patientId==\"$ESC1\")][0]|keys|sort)==[\"alertId\",\"deliveryCursor\",\"nivel\",\"patientId\",\"timestamp\"]"
  assert_jq_true "[.[]|select(.patientId==\"$ESC1\")][0].nivel==\"CRITICO\""
  assert_jq_true '([.[].deliveryCursor] == ([.[].deliveryCursor]|sort))'
  local cur; cur=$(jq -r ".[]|select(.patientId==\"$ESC1\")|.deliveryCursor" <<<"$BODY")
  cuando "se pide despuesDe=$cur"; treq GET "/triage/alertas?despuesDe=$cur"; assert_status 200
  entonces "no incluye ESC1"; assert_jq_true "all(.[]; .patientId != \"$ESC1\")"
  cuando "se pide despuesDe=$((cur - 1)) y limite=1"; treq GET "/triage/alertas?despuesDe=$((cur - 1))&limite=1"
  entonces "devuelve solo esa alerta"; assert_status 200; assert_jq length 1; assert_jq '.[0].patientId' "$ESC1"
  local q
  for q in "limite=0" "limite=201" "despuesDe=-1" "limite=abc"; do
    cuando "se envia ?$q"; treq GET "/triage/alertas?$q"; entonces "responde 400 con campo"; assert_status 400; assert_jq_true '.campo != null'
  done
}

sse_get() { # ARCHIVO SEGUNDOS [Last-Event-ID]  -> deja cabeceras en ARCHIVO.hdr
  local h=(-H "X-API-Key: $TRIAGE_API_KEY" -H 'Accept: text/event-stream'); [[ -n "${3-}" ]] && h+=(-H "Last-Event-ID: $3")
  curl -sN --max-time "$2" -D "$1.hdr" "${h[@]}" "$TRIAGE_URL/triage/alertas/stream" > "$1" 2>/dev/null || true
}
sc_sse() {
  load_esc; wait_escalated
  dado "un cliente SSE conectado antes del escalamiento (sin Last-Event-ID)"
  cuando "ESC1 se escala"
  poll_until 20 "grep -q '$ESC1' '$WORK/sse_live.txt'" || fail "el stream en vivo no recibio la alerta de ESC1"
  entonces "recibe event: alerta con id=deliveryCursor y data de 5 claves"
  local cur; cur=$(grep -B2 "$ESC1" "$WORK/sse_live.txt" | grep '^id:' | head -1 | sed 's/^id: *//' | tr -d '\r')
  [[ -n "$cur" ]] || fail "no se encontro id: en el stream"
  grep -q '^event:alerta\|^event: alerta' "$WORK/sse_live.txt" || fail "falta event: alerta"
  local data; data=$(grep "$ESC1" "$WORK/sse_live.txt" | head -1 | sed 's/^data: *//' | tr -d '\r')
  jq -e ".patientId==\"$ESC1\" and .deliveryCursor==$cur and (keys|sort)==[\"alertId\",\"deliveryCursor\",\"nivel\",\"patientId\",\"timestamp\"]" <<<"$data" >/dev/null \
    || fail "data inesperado: $data"
  cuando "el cliente reconecta con Last-Event-ID=$((cur - 1))"; sse_get "$WORK/sse_r1.txt" 4 "$((cur - 1))"
  entonces "recibe de nuevo la alerta de ESC1 (recuperacion)"
  grep -q "$ESC1" "$WORK/sse_r1.txt" || fail "no se recupero la alerta con Last-Event-ID"
  grep -qi '^content-type:.*text/event-stream' "$WORK/sse_r1.txt.hdr" || fail "content-type no es text/event-stream"
  cuando "reconecta con Last-Event-ID=$cur"; sse_get "$WORK/sse_r2.txt" 4 "$cur"
  entonces "no se repite ESC1"; if grep -q "$ESC1" "$WORK/sse_r2.txt"; then fail "ESC1 se repitio"; fi
  cuando "reconecta con un Last-Event-ID mayor al maximo (999999)"; sse_get "$WORK/sse_r3.txt" 18 999999
  entonces "el stream abre (200), no queda mudo ni 400"; head -1 "$WORK/sse_r3.txt.hdr" | grep -q ' 200' || fail "no abrio: $(head -1 "$WORK/sse_r3.txt.hdr")"
  y "sin alertas nuevas llega el comentario :heartbeat (cada 15 s)"; grep -q '^:heartbeat' "$WORK/sse_r3.txt" || fail "sin heartbeat en 18 s"
  cuando "reconecta sin Last-Event-ID"; sse_get "$WORK/sse_r4.txt" 3
  entonces "solo ve alertas nuevas (no repite ESC1)"; if grep -q "$ESC1" "$WORK/sse_r4.txt"; then fail "reenvio historico sin Last-Event-ID"; fi
}

sc_sse_errors() {
  local v
  cuando "se abre el stream sin X-API-Key"; req GET "$TRIAGE_URL/triage/alertas/stream" ""
  entonces "responde 401 con cuerpo vacio"; assert_status 401; assert_eq "$BODY" "" cuerpo
  for v in "abc" "-1" "+5" "1234567890123456789"; do
    cuando "Last-Event-ID='$v'"; treq GET /triage/alertas/stream "" "Last-Event-ID: $v" "Accept: text/event-stream"
    entonces "responde 400 con campo Last-Event-ID"; assert_status 400; assert_jq .campo "Last-Event-ID"
  done
}

# ---------------------------------------------------------------- S21..S24 Asistente (RAG)
sc_rag() {
  local q src
  local cases=("¿Cuáles son las medidas iniciales ante una crisis hipertensiva?|PROTO-001"
               "¿Cuándo se administra el primer antibiótico en sepsis?|PROTO-002"
               "¿Cuál es la rehidratación en deshidratación pediátrica?|PROTO-003")
  local c
  for c in "${cases[@]}"; do
    q="${c%|*}"; src="${c##*|}"
    dado "la pregunta '$q'"; cuando "se envia a POST /asistente/consulta"
    areq POST /asistente/consulta "$(jq -nc --arg p "$q" '{pregunta:$p}')"
    entonces "responde 200 con contenido y fuente $src (protocolo + seccion)"
    assert_status 200
    assert_jq_true '(.respuesta|length)>0 and (.fuentes|length)>=1'
    assert_jq_true "any(.fuentes[]; .protocolo==\"$src\" and (.seccion|length)>0)"
    assert_jq_true 'all(.fuentes[]; .protocolo|test("^PROTO-00[123]$"))'
    assert_jq_true '(.respuesta|test("No existe un protocolo")|not)'
  done
  y "(proveedor LLM = doble de desarrollo: valida cita/ruteo, no calidad del texto)"
}
sc_abstention() {
  local q AV="No existe un protocolo disponible para responder esta consulta."
  for q in "¿Cuál es el tratamiento de primera línea para la diabetes tipo 2?" "¿Cuál es la capital de Francia?" "asdf qwer zxcv"; do
    dado "una pregunta sin protocolo: '$q'"; cuando "se consulta"
    areq POST /asistente/consulta "$(jq -nc --arg p "$q" '{pregunta:$p}')"
    entonces "responde 200 solo con el aviso fijo y fuentes vacias (RN-05)"
    assert_status 200; assert_jq .respuesta "$AV"; assert_jq '.fuentes|length' 0
  done
}
sc_rag_invalid() {
  cuando "la pregunta esta vacia"; areq POST /asistente/consulta '{"pregunta":""}'
  entonces "responde 400 con campo pregunta"; assert_status 400; assert_jq .campo pregunta
  cuando "falta la pregunta"; areq POST /asistente/consulta '{}'; assert_status 400
  cuando "supera 500 caracteres"; areq POST /asistente/consulta "$(jq -nc '{pregunta:("a"*501)}')"
  entonces "responde 400"; assert_status 400; assert_jq .campo pregunta
  cuando "el JSON esta mal formado"; areq POST /asistente/consulta '{no-json'; entonces "responde 400"; assert_status 400
}
sc_llm_down() {
  local i
  dado "el Asistente con proveedor LLM inalcanzable"
  for i in 1 2 3 4 5 6 7; do
    cuando "se consulta (intento $i)"
    areq POST /asistente/consulta '{"pregunta":"¿Cuáles son las medidas iniciales ante una crisis hipertensiva?"}'
    entonces "responde 503 con mensaje claro, sin contenido clinico ni detalle del proveedor"
    assert_status 503; assert_jq .title "Servicio no disponible"; assert_jq_true '(.detail|test("no esta disponible"))'
    [[ "$BODY" != *"127.0.0.1"* && "$BODY" != *"PROTO-"* && "$BODY" != *"azure"* ]] || fail "el 503 filtra detalle: $BODY"
  done
  y "validacion y estado-urgencias siguen vivos (no dependen del LLM)"
  areq POST /asistente/consulta '{"pregunta":""}'; assert_status 400
  areq GET /asistente/estado-urgencias ""; assert_status 200
}
