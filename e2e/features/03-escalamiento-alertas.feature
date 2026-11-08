# language: es
# Fuente: RF-03, RF-06, RN-02, RN-03, RN-04. SLA de CRITICO = 1 min e intervalo 2 s por e2e/compose.e2e.yml.
Feature: Escalamiento por SLA, alertas persistidas y SSE

  @S14
  Scenario: Un paciente se escala una sola vez
    Given un paciente CRITICO (SLA 1 min) que sigue REGISTRADO
    When transcurre el SLA y corre el proceso periódico
    Then el flag escalado queda true y el estado sigue REGISTRADO
    And hay exactamente una auditoría paciente_escalado, un outbox, una alerta y un evento Kafka
    And tras más pasadas del proceso nada se duplica
    And si luego entra a consulta el flag se conserva

  @S15
  Scenario: Nunca se escala EN_CONSULTA ni ALTA
    Given pacientes CRITICO que entraron a consulta o recibieron alta antes del SLA
    When el SLA vence y el proceso periódico ya corrió
    Then escalado sigue false, sin auditoría ni alerta de escalamiento

  @S16
  Scenario: Alertas persistidas
    Given un paciente escalado
    When se consulta GET /triage/alertas
    Then trae su alerta con solo alertId, deliveryCursor, patientId, nivel y timestamp, en orden ascendente
    And despuesDe filtra por cursor y limite acota la página
    And limite 0/201, despuesDe negativo o no numérico responden 400 con campo

  @S17
  Scenario: SSE en vivo y recuperación con Last-Event-ID
    Given un cliente SSE conectado antes del escalamiento
    When el paciente se escala
    Then recibe "event: alerta" con id igual al deliveryCursor y data de 5 claves
    And al reconectar con Last-Event-ID anterior recibe de nuevo la alerta
    And con Last-Event-ID igual al cursor no se repite
    And con un id mayor al máximo el stream abre
    And sin Last-Event-ID solo ve alertas nuevas

  @S18
  Scenario: SSE rechaza solicitudes inválidas
    When se abre el stream sin X-API-Key
    Then responde 401 con cuerpo vacío
    And un Last-Event-ID no numérico, negativo, con signo o de más de 18 dígitos responde 400 con campo "Last-Event-ID"
