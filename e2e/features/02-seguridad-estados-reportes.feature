# language: es
# Fuente: docs/requirements/technical-test-baseline.md (RF-02, RF-05, RN-04) y ACs de las historias 1.3, 2.1, 3.x.
Feature: Autenticación, estados y reportes

  @S08
  Scenario: Sin X-API-Key se responde 401
    Given los endpoints de Triage y del Asistente
    When se invocan sin X-API-Key, con una clave incorrecta o con la clave del otro servicio
    Then responden 401 con cuerpo vacío
    And las sondas /actuator/health responden 200 sin clave

  @S09
  Scenario: Transiciones válidas de estado
    Given un paciente REGISTRADO
    When pasa a EN_CONSULTA y luego a ALTA
    Then cada PATCH responde 200 con el estado nuevo
    And hay 3 auditorías en orden: registro, consulta, alta
    And un paciente REGISTRADO puede recibir alta directa (2 auditorías)

  @S10
  Scenario: Las transiciones inválidas responden 409 sin efectos
    Given un paciente en EN_CONSULTA o ALTA, o REGISTRADO pidiendo REGISTRADO
    When se solicita EN_CONSULTA->REGISTRADO, EN_CONSULTA->EN_CONSULTA, ALTA->cualquiera o REGISTRADO->REGISTRADO
    Then responde 409 con estadoActual y estadoSolicitado
    And el paciente no cambia y no se agrega auditoría

  @S11
  Scenario: Estado desconocido o paciente inexistente
    Given un paciente registrado
    When el estado es desconocido, falta, el id no existe o no es UUID
    Then responde 400 (estado/UUID) o 404 (inexistente) y el paciente no cambia

  @S12
  Scenario: Reporte de operación por hora
    Given 3 pacientes nuevos (CRITICO, URGENTE, NO_URGENTE) y uno entra a consulta
    When se consulta GET /triage/reportes/operacion con un rango amplio
    Then el total sube 3 y cada nivel sube 1
    And las filas traen hora, porNivel, promedio y p95 (no nulos si hubo consulta)
    And una ventana sin pacientes devuelve []

  @S13
  Scenario: Reporte con parámetros inválidos
    When falta desde o hasta, la fecha no es ISO 8601 o desde >= hasta
    Then responde 400 con "campo" igual al parámetro inválido
