# language: es
# Fuente: docs/requirements/technical-test-baseline.md (RF-02, RF-05, RN-04) y ACs de las historias 1.3, 2.1, 3.x.
Característica: Autenticación, estados y reportes

  @S08
  Escenario: Sin X-API-Key se responde 401
    Dado los endpoints de Triage y del Asistente
    Cuando se invocan sin X-API-Key, con una clave incorrecta o con la clave del otro servicio
    Entonces responden 401 con cuerpo vacío
    Y las sondas /actuator/health responden 200 sin clave

  @S09
  Escenario: Transiciones válidas de estado
    Dado un paciente REGISTRADO
    Cuando pasa a EN_CONSULTA y luego a ALTA
    Entonces cada PATCH responde 200 con el estado nuevo
    Y hay 3 auditorías en orden: registro, consulta, alta
    Y un paciente REGISTRADO puede recibir alta directa (2 auditorías)

  @S10
  Escenario: Las transiciones inválidas responden 409 sin efectos
    Dado un paciente en EN_CONSULTA o ALTA, o REGISTRADO pidiendo REGISTRADO
    Cuando se solicita EN_CONSULTA->REGISTRADO, EN_CONSULTA->EN_CONSULTA, ALTA->cualquiera o REGISTRADO->REGISTRADO
    Entonces responde 409 con estadoActual y estadoSolicitado
    Y el paciente no cambia y no se agrega auditoría

  @S11
  Escenario: Estado desconocido o paciente inexistente
    Dado un paciente registrado
    Cuando el estado es desconocido, falta, el id no existe o no es UUID
    Entonces responde 400 (estado/UUID) o 404 (inexistente) y el paciente no cambia

  @S12
  Escenario: Reporte de operación por hora
    Dado 3 pacientes nuevos (CRITICO, URGENTE, NO_URGENTE) y uno entra a consulta
    Cuando se consulta GET /triage/reportes/operacion con un rango amplio
    Entonces el total sube 3 y cada nivel sube 1
    Y las filas traen hora, porNivel, promedio y p95 (no nulos si hubo consulta)
    Y una ventana sin pacientes devuelve []

  @S13
  Escenario: Reporte con parámetros inválidos
    Cuando falta desde o hasta, la fecha no es ISO 8601 o desde >= hasta
    Entonces responde 400 con "campo" igual al parámetro inválido
