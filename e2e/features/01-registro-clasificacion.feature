# Fuente: docs/requirements/technical-test-baseline.md (RF-01, RN-01, RN-02, E1-E6). Implementado en e2e/scenarios.sh.
Feature: Registro y clasificación de pacientes (RF-01, RN-01, RN-02)
  Como personal de admisión
  Quiero registrar pacientes y recibir su nivel de triaje y SLA
  Para priorizar la atención

  @S01
  Scenario Outline: Clasificación de los casos de referencia E1-E6
    Given el caso "<caso>" con la queja "<queja>" y signos FC=<fc> sis=<sis> dia=<dia> SpO2=<spo2> T=<temp> dolor=<dolor> GCS=<gcs>
    When se registra con POST /triage/pacientes
    Then responde 201 con nivel "<nivel>" y estado "REGISTRADO"

    Examples:
      | caso | fc  | sis | dia | spo2 | temp | dolor | gcs | queja                    | nivel        |
      | E1   | 130 | 180 | 110 | 97   | 36.8 | 8     | 15  | Dolor torácico           | CRITICO      |
      | E2   | 95  | 150 | 95  | 91   | 37.0 | 4     | 15  | Dificultad para respirar | URGENTE      |
      | E3   | 88  | 130 | 85  | 98   | 38.2 | 6     | 15  | Fiebre                   | URGENTE_2    |
      | E4   | 72  | 120 | 80  | 99   | 36.5 | 2     | 15  | Herida por cortar        | NO_URGENTE   |
      | E5   | 70  | 115 | 75  | 99   | 36.4 | 0     | 15  | Renovación de recetario  | NO_URGENTE_2 |
      | E6   | 35  | 80  | 50  | 85   | 35.9 | 0     | 6   | Inconsciente             | CRITICO      |

  @S02
  Scenario: Fronteras inclusivas/exclusivas de RN-01 y precedencia del nivel más grave
    Given signos sanos de NO_URGENTE y una variable en la frontera
    When se registra cada paciente
    Then FC 39 y 121 son CRITICO; FC 40 es NO_URGENTE; FC 90 y 120 son URGENTE_2
    And SpO2 89 es CRITICO; 90 y 93 son URGENTE; 94 es NO_URGENTE
    And sistólica 89 es CRITICO; 90 y 100 son URGENTE; 101 es NO_URGENTE
    And temperatura 38.5 es URGENTE; 38.0 y 38.4 son URGENTE_2; 37.9 es NO_URGENTE
    And GCS 8 es CRITICO; 9 y 13 son URGENTE; 14 es NO_URGENTE
    And dolor 7 es URGENTE_2; 6 es NO_URGENTE
    And si se cumplen varios niveles prevalece el más grave

  @S03
  Scenario: Una queja administrativa solo da nivel 5 si no cumple niveles 1-3
    Given signos vitales sanos
    When la queja es documentos, recolección de resultados, renovación de recetario, vacunación o seguimiento
    Then el nivel es NO_URGENTE_2
    And una queja no administrativa queda en NO_URGENTE
    And una queja administrativa con FC 130 es CRITICO, con SpO2 92 es URGENTE y con T 38.1 es URGENTE_2

  @S04
  Scenario: La respuesta de registro trae id, nivel, SLA, estado y fecha ISO 8601
    Given un paciente de cada nivel
    When se registra
    Then la respuesta trae id UUID, nivel, slaMinutos, estado "REGISTRADO" y fechaRegistro ISO 8601
    And slaMinutos es 10, 30, 60 y 120 para URGENTE, URGENTE_2, NO_URGENTE y NO_URGENTE_2
    And slaMinutos de CRITICO es 1 porque el entorno E2E acorta el SLA (RN-02 define 5)

  @S05
  Scenario: Consultar un paciente
    Given un paciente registrado
    When se consulta GET /triage/pacientes/{id}
    Then trae datos completos, estado, flags.escalado=false y minutosEnEstadoActual
    But un id inexistente responde 404 y un id que no es UUID responde 400

  @S06
  Scenario: Un dato fuera de rango responde 400 e identifica el campo
    Given un paciente válido al que se le invalida un campo
    When se envía con FC 19/251, sistólica 59/261, diastólica 29/161 o >= sistólica, SpO2 69/101, T 33.9/42.1, dolor -1/11, GCS 2/16, edad -1/121, nombre de 2 o 101 caracteres, o campos ausentes
    Then responde 400 y el cuerpo trae "campo" con el nombre del campo inválido
    And un JSON mal formado responde 400

  @S07
  Scenario: Los valores exactamente en el límite válido se aceptan
    Given valores en el límite de cada rango de RF-01
    When se registra
    Then responde 201
