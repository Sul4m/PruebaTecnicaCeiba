# language: es
# Fuente: RF-03, RF-06, RN-02, RN-03, RN-04. SLA de CRITICO = 1 min e intervalo 2 s por e2e/compose.e2e.yml.
Característica: Escalamiento por SLA, alertas persistidas y SSE

  @S14
  Escenario: Un paciente se escala una sola vez
    Dado un paciente CRITICO (SLA 1 min) que sigue REGISTRADO
    Cuando transcurre el SLA y corre el proceso periódico
    Entonces el flag escalado queda true y el estado sigue REGISTRADO
    Y hay exactamente una auditoría paciente_escalado, un outbox, una alerta y un evento Kafka
    Y tras más pasadas del proceso nada se duplica
    Y si luego entra a consulta el flag se conserva

  @S15
  Escenario: Nunca se escala EN_CONSULTA ni ALTA
    Dado pacientes CRITICO que entraron a consulta o recibieron alta antes del SLA
    Cuando el SLA vence y el proceso periódico ya corrió
    Entonces escalado sigue false, sin auditoría ni alerta de escalamiento

  @S16
  Escenario: Alertas persistidas
    Dado un paciente escalado
    Cuando se consulta GET /triage/alertas
    Entonces trae su alerta con solo alertId, deliveryCursor, patientId, nivel y timestamp, en orden ascendente
    Y despuesDe filtra por cursor y limite acota la página
    Y limite 0/201, despuesDe negativo o no numérico responden 400 con campo

  @S17
  Escenario: SSE en vivo y recuperación con Last-Event-ID
    Dado un cliente SSE conectado antes del escalamiento
    Cuando el paciente se escala
    Entonces recibe "event: alerta" con id igual al deliveryCursor y data de 5 claves
    Y al reconectar con Last-Event-ID anterior recibe de nuevo la alerta
    Y con Last-Event-ID igual al cursor no se repite
    Y con un id mayor al máximo el stream abre
    Y sin Last-Event-ID solo ve alertas nuevas

  @S18
  Escenario: SSE rechaza solicitudes inválidas
    Cuando se abre el stream sin X-API-Key
    Entonces responde 401 con cuerpo vacío
    Y un Last-Event-ID no numérico, negativo, con signo o de más de 18 dígitos responde 400 con campo "Last-Event-ID"
