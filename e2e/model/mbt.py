#!/usr/bin/env python3
"""MBT del ciclo de vida del paciente (RF-02/RF-03/RN-03) contra el stack real, con curl.

Lee patient_model.json (estados = (estado, escalado); transiciones validas, invalidas y de tiempo), genera un caso por
transicion (camino mas corto desde el estado inicial + la transicion = cobertura de TODAS las transiciones),
los ejecuta con curl y compara el oraculo del modelo (estado, escalado, HTTP esperado, alertas) en cada paso.
Al final comprueba el oraculo de Kafka: el conteo activo del Asistente sube segun los pacientes que NO terminan en ALTA.

Uso: mbt.py --triage URL --assistant URL --triage-key K --assistant-key K --sla-seconds 60 --model patient_model.json
Salida 0 si todos los casos pasan y la cobertura de transiciones es 100 %.
"""
import argparse
import collections
import concurrent.futures
import json
import subprocess
import sys
import time

MARGIN = 10      # segundos tras SLA para dar tiempo al scheduler (intervalo 2 s)
STABLE = 6       # segundos extra para comprobar que NO cambia nada (idempotencia / nunca escalar)
PAYLOAD = {"nombreCompleto": "Paciente MBT", "edad": 50, "quejaPrincipal": "Dolor toracico",
           "signosVitales": {"frecuenciaCardiaca": 130, "presionSistolica": 180, "presionDiastolica": 110,
                             "spo2": 97, "temperatura": 36.8, "dolor": 8, "gcs": 15}}


def curl(method, url, key, body=None):
    cmd = ["curl", "-sS", "--max-time", "40", "-X", method, "-o", "-", "-w", "\n%{http_code}",
           "-H", f"X-API-Key: {key}", url]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    out = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout
    text, _, code = out.rpartition("\n")
    try:
        return int(code), (json.loads(text) if text.strip() else None)
    except json.JSONDecodeError:
        return int(code), text


class Failure(Exception):
    pass


def check(cond, msg):
    if not cond:
        raise Failure(msg)


class Ctx:
    def __init__(self, a, model):
        self.a, self.model = a, model
        self.states = {s["id"]: s for s in model["states"]}
        self.out = collections.defaultdict(dict)  # (from, action) -> transition
        for t in model["transitions"]:
            self.out[t["from"]][t["action"]] = t

    def t(self, method, path, body=None):
        return curl(method, self.a.triage + path, self.a.triage_key, body)

    def alert_count(self, pid):
        n, cursor = 0, 0
        while True:
            code, rows = self.t("GET", f"/triage/alertas?despuesDe={cursor}&limite=200")
            check(code == 200, f"GET alertas -> {code}")
            n += sum(1 for r in rows if r["patientId"] == pid)
            if len(rows) < 200:
                return n
            cursor = rows[-1]["deliveryCursor"]


def plan(ctx):
    """BFS sobre transiciones NO erroneas (valida/tiempo con cambio) -> camino minimo a cada estado."""
    start = ctx.model["initial"]
    path = {start: []}
    queue = collections.deque([start])
    while queue:
        s = queue.popleft()
        for action, t in ctx.out[s].items():
            if t["to"] != s and t["to"] not in path:
                path[t["to"]] = path[s] + [action]
                queue.append(t["to"])
    cases = []
    for t in ctx.model["transitions"]:  # un caso por transicion del modelo => cobertura de transiciones
        cases.append({"id": f'{t["from"]}--{t["action"]}-->{t["to"]}', "prefix": path[t["from"]],
                      "edge": t["action"], "start_state": t["from"]})
    return cases


def run_step(ctx, st, pid, t_reg, sla, current, action, edge_cov):
    """Ejecuta una accion desde el estado del modelo `current`; devuelve el estado siguiente segun el modelo."""
    tr = ctx.out[current][action]
    edge_cov.add((current, action))
    exp = ctx.states[tr["to"]]
    if action == "WAIT_SLA":
        first = not st["waited"]
        wake = t_reg + sla + MARGIN
        time.sleep(max(0, wake - time.time()))
        if tr["to"] != current:  # REGISTRADO sin escalar -> debe escalar
            deadline = time.time() + 30
            while time.time() < deadline:
                code, p = ctx.t("GET", f"/triage/pacientes/{pid}")
                if code == 200 and p["flags"]["escalado"]:
                    break
                time.sleep(1)
        else:
            time.sleep(STABLE)  # no debe cambiar nada (idempotencia o nunca escalar)
        st["waited"] = True
    else:
        estado = action.split(":", 1)[1]
        wire = "FOO_NO_EXISTE" if estado == "DESCONOCIDO" else estado
        code, body = ctx.t("PATCH", f"/triage/pacientes/{pid}/estado", {"estado": wire})
        check(code == tr["http"], f"{action} desde {current}: HTTP {code} esperado {tr['http']} ({body})")
        if code == 200:
            check(body["estado"] == exp["estado"], f"{action}: estado {body['estado']} != {exp['estado']}")
        if code == 409:
            check(body.get("estadoActual") == ctx.states[current]["estado"], f"409 estadoActual: {body}")
    # Oraculo: GET refleja estado y escalado del modelo
    code, p = ctx.t("GET", f"/triage/pacientes/{pid}")
    check(code == 200, f"GET -> {code}")
    check(p["estado"] == exp["estado"], f"tras {action}: estado {p['estado']} != modelo {exp['estado']}")
    check(p["flags"]["escalado"] == exp["escalado"],
          f"tras {action}: escalado {p['flags']['escalado']} != modelo {exp['escalado']}")
    if action == "WAIT_SLA":
        n = ctx.alert_count(pid)
        check(n == (1 if exp["escalado"] else 0), f"alertas del paciente = {n}, modelo escalado={exp['escalado']}")
    return tr["to"]


def run_case(ctx, case, sla, edge_cov):
    code, body = ctx.t("POST", "/triage/pacientes", PAYLOAD)
    check(code == 201 and body["nivel"] == "CRITICO", f"registro -> {code} {body}")
    pid, t_reg = body["id"], time.time()
    st, cur = {"waited": False}, ctx.model["initial"]
    for action in case["prefix"] + [case["edge"]]:
        cur = run_step(ctx, st, pid, t_reg, sla, cur, action, edge_cov)
    return cur


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--triage", required=True)
    ap.add_argument("--assistant", required=True)
    ap.add_argument("--triage-key", required=True)
    ap.add_argument("--assistant-key", required=True)
    ap.add_argument("--sla-seconds", type=int, default=60)
    ap.add_argument("--model", required=True)
    a = ap.parse_args()
    model = json.load(open(a.model))
    ctx = Ctx(a, model)

    # Consistencia del modelo: cada estado define todas las acciones
    actions = {t["action"] for t in model["transitions"]}
    for s in ctx.states:
        missing = actions - set(ctx.out[s])
        if missing:
            print(f"MODELO INCOMPLETO: {s} sin {missing}")
            return 2

    cases = plan(ctx)
    print(f"MBT: {len(ctx.states)} estados, {len(model['transitions'])} transiciones -> {len(cases)} casos")

    def asist():
        c, b = curl("GET", a.assistant + "/asistente/estado-urgencias", a.assistant_key)
        return b["totalActivos"] if c == 200 else None
    time.sleep(2)
    base = asist()

    edge_cov, results, finals = set(), {}, {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as ex:
        futs = {ex.submit(run_case, ctx, c, a.sla_seconds, edge_cov): c for c in cases}
        for f in concurrent.futures.as_completed(futs):
            c = futs[f]
            try:
                finals[c["id"]] = f.result()
                results[c["id"]] = None
            except Exception as e:  # noqa: BLE001
                results[c["id"]] = f"{type(e).__name__}: {e}"

    # Caso global: paciente inexistente -> 404
    code, _ = ctx.t("PATCH", "/triage/pacientes/00000000-0000-4000-8000-000000000000/estado", {"estado": "ALTA"})
    if code != 404:
        results["PATCH id inexistente"] = f"HTTP {code} esperado 404"
    else:
        results["PATCH id inexistente"] = None

    # Oraculo Kafka/Asistente: activos esperados = casos cuyo estado final del modelo no es ALTA
    expected_active = sum(1 for cid, fin in finals.items() if ctx.states[fin]["estado"] != "ALTA")
    kafka_msg = None
    if base is not None:
        deadline, got = time.time() + 40, None
        while time.time() < deadline:
            got = asist()
            if got == base + expected_active:
                break
            time.sleep(1)
        if got != base + expected_active:
            kafka_msg = f"totalActivos del Asistente={got}, esperado {base}+{expected_active}"
    else:
        kafka_msg = "no se pudo leer estado-urgencias"

    print()
    for c in cases:
        r = results.get(c["id"])
        print(f'  {"PASS" if r is None else "FAIL"}  {c["id"]}  camino={c["prefix"]}' + ("" if r is None else f"\n        {r}"))
    print(f'  {"PASS" if results["PATCH id inexistente"] is None else "FAIL"}  PATCH id inexistente -> 404')
    print(f'  {"PASS" if kafka_msg is None else "FAIL"}  oraculo Kafka: activos esperados +{expected_active}'
          + ("" if kafka_msg is None else f" ({kafka_msg})"))

    all_edges = {(t["from"], t["action"]) for t in model["transitions"]}
    kinds = collections.Counter(t["kind"] for t in model["transitions"])
    cov = len(edge_cov & all_edges)
    failed = [k for k, v in results.items() if v] + ([kafka_msg] if kafka_msg else [])
    print(f"\nCobertura de transiciones: {cov}/{len(all_edges)} "
          f"(validas={kinds['valida']}, invalidas={kinds['invalida']}, tiempo={kinds['tiempo']}) ; "
          f"estados alcanzados: {len({s for s in finals.values()})}/{len(ctx.states)} como destino final")
    print(f"MBT casos: {len(cases) + 1 - len([k for k, v in results.items() if v])} pass, {len([k for k, v in results.items() if v])} fail")
    return 1 if failed or cov != len(all_edges) else 0


if __name__ == "__main__":
    sys.exit(main())
