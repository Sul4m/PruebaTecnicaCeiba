# language: es
# Fuente: RF-04, RN-05, historias 3.3, 4.1-4.3. El proveedor LLM local es un doble de desarrollo (perfil dev).
Característica: Eventos Kafka, estado de urgencias y Asistente Clínico (RAG)

  @S19
  Escenario: El conteo del Asistente sube al registrar y baja al dar de alta
    Dado el conteo actual de URGENTE en el Asistente
    Cuando se registra un paciente URGENTE en Triage
    Entonces el Asistente, vía Kafka, sube URGENTE y totalActivos en 1
    Y en EN_CONSULTA sigue contando
    Y al dar de alta el conteo baja en 1
    Y un NO_URGENTE_2 con alta directa sube y baja
    Y la respuesta siempre trae los 5 niveles

  @S20
  Escenario: Eventos publicados en triaje.eventos
    Dado un paciente que se registra, entra a consulta y recibe alta
    Cuando se lee el topic triaje.eventos
    Entonces hay 3 eventos con clave patientId, tipos paciente_registrado, paciente_en_consulta, paciente_alta y versiones 1, 2, 3
    Y el envelope es schemaVersion 1 con payload patientId, nivel, estado, escalated
    Y no incluye nombre, edad, queja ni signos vitales

  @S21
  Escenario: Consulta RAG con cita del protocolo
    Dado una pregunta clínica cubierta por el corpus (hipertensiva, sepsis, deshidratación pediátrica)
    Cuando se envía a POST /asistente/consulta
    Entonces responde 200 con respuesta no vacía
    Y fuentes incluye el PROTO-001/002/003 correspondiente con sección
    Y toda fuente pertenece al corpus

  @S22
  Escenario: Abstención cuando no existe protocolo (RN-05)
    Dado una pregunta sin protocolo (diabetes tipo 2, capital de Francia, texto sin sentido)
    Cuando se consulta
    Entonces responde 200 solo con el aviso "No existe un protocolo disponible para responder esta consulta." y fuentes vacías

  @S23
  Escenario: Consulta inválida
    Cuando la pregunta está vacía, ausente, supera 500 caracteres o el JSON está mal formado
    Entonces responde 400 (con campo "pregunta" cuando aplica)

  @S24
  Escenario: Proveedor LLM caído responde 503 claro
    Dado el Asistente con perfil azure y un endpoint de proveedor inalcanzable
    Cuando se consulta varias veces (hasta abrir el circuit breaker)
    Entonces siempre responde 503 "Servicio no disponible" sin contenido clínico ni detalle del proveedor
    Y la validación (400) y GET /asistente/estado-urgencias siguen funcionando
