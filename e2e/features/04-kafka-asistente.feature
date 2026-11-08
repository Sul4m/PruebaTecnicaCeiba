# Fuente: RF-04, RN-05, historias 3.3, 4.1-4.3. El proveedor LLM local es un doble de desarrollo (perfil dev).
Feature: Eventos Kafka, estado de urgencias y Asistente Clínico (RAG)

  @S19
  Scenario: El conteo del Asistente sube al registrar y baja al dar de alta
    Given el conteo actual de URGENTE en el Asistente
    When se registra un paciente URGENTE en Triage
    Then el Asistente, vía Kafka, sube URGENTE y totalActivos en 1
    And en EN_CONSULTA sigue contando
    And al dar de alta el conteo baja en 1
    And un NO_URGENTE_2 con alta directa sube y baja
    And la respuesta siempre trae los 5 niveles

  @S20
  Scenario: Eventos publicados en triaje.eventos
    Given un paciente que se registra, entra a consulta y recibe alta
    When se lee el topic triaje.eventos
    Then hay 3 eventos con clave patientId, tipos paciente_registrado, paciente_en_consulta, paciente_alta y versiones 1, 2, 3
    And el envelope es schemaVersion 1 con payload patientId, nivel, estado, escalated
    And no incluye nombre, edad, queja ni signos vitales

  @S21
  Scenario: Consulta RAG con cita del protocolo
    Given una pregunta clínica cubierta por el corpus (hipertensiva, sepsis, deshidratación pediátrica)
    When se envía a POST /asistente/consulta
    Then responde 200 con respuesta no vacía
    And fuentes incluye el PROTO-001/002/003 correspondiente con sección
    And toda fuente pertenece al corpus

  @S22
  Scenario: Abstención cuando no existe protocolo (RN-05)
    Given una pregunta sin protocolo (diabetes tipo 2, capital de Francia, texto sin sentido)
    When se consulta
    Then responde 200 solo con el aviso "No existe un protocolo disponible para responder esta consulta." y fuentes vacías

  @S23
  Scenario: Consulta inválida
    When la pregunta está vacía, ausente, supera 500 caracteres o el JSON está mal formado
    Then responde 400 (con campo "pregunta" cuando aplica)

  @S24
  Scenario: Proveedor LLM caído responde 503 claro
    Given el Asistente con perfil azure y un endpoint de proveedor inalcanzable
    When se consulta varias veces (hasta abrir el circuit breaker)
    Then siempre responde 503 "Servicio no disponible" sin contenido clínico ni detalle del proveedor
    And la validación (400) y GET /asistente/estado-urgencias siguen funcionando
