---
title: "Product Brief: VidaCruz - triaje digital y asistente clínico"
status: "draft"
created: "2026-11-07"
updated: "2026-11-07"
inputs:
  - "Enunciado de la prueba técnica Dev. Webflux, fechado 2026-10-29 (PDF fuente externo al repositorio)"
---

# Product Brief: VidaCruz - triaje digital y asistente clínico

## Resumen ejecutivo

VidaCruz, escenario de la prueba técnica, opera 12 salas de urgencias y describe tres problemas en el proceso manual de triaje: variación en la clasificación, falta de trazabilidad del recorrido y dificultad para consultar protocolos clínicos internos. La iniciativa propone un backend con dos servicios independientes: Triage, que registra y clasifica pacientes y controla su atención, y Asistente Clínico, que consulta un corpus de protocolos con respuestas sustentadas en fuentes.

El resultado esperado de la prueba no es una herramienta clínica lista para producción. Es una demostración funcional que valide reglas objetivas, registro auditable, escalamiento por SLA, integración asíncrona, consulta RAG con citas y modo seguro cuando no exista evidencia o el proveedor de IA falle. El alcance y las reglas provienen del enunciado; no se asumen resultados clínicos, regulatorios ni comerciales fuera de él.

## Problema

El flujo descrito depende de valoración manual y registro en papel. El escenario señala inconsistencias percibidas en la prioridad de pacientes, falta de historial del estado y del tiempo de espera, y acceso limitado a protocolos cuando el médico necesita consultarlos. Las personas principalmente afectadas son pacientes en urgencias, personal de enfermería que registra el triaje y médicos que atienden o consultan protocolos.

## Usuarios y necesidades

- **Enfermería / admisión:** registrar signos vitales y queja principal, recibir una clasificación repetible y conocer el SLA asignado.
- **Equipo médico:** conocer la carga activa por nivel y consultar protocolos internos sin que el asistente invente orientación clínica.
- **Operaciones / auditoría:** reconstruir transiciones, escalamiento, tiempos y actividad agregada por hora.
- **Evaluador técnico:** verificar flujos de negocio mediante APIs, eventos, pruebas automatizadas y despliegue reproducible.

## Solución y valor esperado

Dos servicios backend exponen las capacidades pedidas. Triage registra pacientes, aplica RN-01/RN-02, permite transiciones clínicas válidas, audita cambios, escala casos que excedan el SLA, genera reportes de operación y alerta al equipo médico mediante SSE con recuperación de alertas persistidas. El Asistente responde consultas usando el corpus dado y expone el conteo de pacientes activos por nivel reconstruido desde eventos. Una respuesta clínica debe citar un protocolo válido; si no hay coincidencia, debe abstenerse.

El valor que se busca demostrar es consistencia y trazabilidad del flujo, junto con acceso acotado a información clínica de referencia. Triage notificará los escalamientos mediante SSE y conservará alertas consultables tras desconexiones. El Asistente utilizará Spring AI con GPT-5.4-mini de Azure OpenAI/Microsoft Foundry para generación RAG. La solución usa arquitectura hexagonal liviana por servicio y se desplegará en AKS con infraestructura Terraform. No se afirma que el sistema mejore resultados de salud ni que reemplace el juicio clínico.

## Alcance de la primera entrega

**Incluye:** las APIs RF-01 a RF-06; estados `REGISTRADO`, `EN_CONSULTA` y `ALTA`; reglas RN-01 a RN-05; eventos Kafka requeridos; reporte horario con promedio y p95 calculados en SQL; RAG sobre los tres protocolos mínimos; seguridad, validación, métricas y probes; pruebas de los casos mínimos; ejecución local con un comando; manifests/IaC y APIs accesibles en Azure.

**Excluye:** frontend, integración con historia clínica electrónica, ampliación del corpus con recomendaciones propias, decisión clínica autónoma, alegaciones de certificación o uso asistencial real. Cualquier uso clínico real requeriría validaciones que esta prueba no especifica.

## Criterios de éxito verificables

- Los casos E1-E6 producen el nivel esperado en RN-01 y se asigna el SLA de RN-02.
- Se rechazan valores fuera de rango con HTTP 400 indicando el campo, y transiciones inválidas con HTTP 409.
- El escalamiento ocurre una sola vez para pacientes aún `REGISTRADO`; una repetición no duplica evento ni auditoría.
- Cada respuesta clínica basada en el corpus incluye `protocolo` y `seccion`; una consulta sin coincidencia no genera contenido clínico.
- Si el proveedor LLM no está disponible, el Asistente devuelve HTTP 503 claro y Triage sigue operando sin depender del Asistente.
- RF-05 agrega por hora en la base de datos total de registros, conteos por nivel, promedio y p95 del tiempo a primera consulta.
- El repositorio ofrece una ejecución local de un comando, pruebas automatizadas, manifiestos/IaC y una URL desplegada accesible.

## Restricciones y puntos por validar

- Stack obligatorio: Java, Spring Boot 3, WebFlux, Reactor, R2DBC con PostgreSQL, Kafka, Resilience4j, Spring AI y Redis o base NoSQL para la vista del Asistente.
- La priorización de RN-01 es por gravedad: niveles 1-3 primero; solo si ninguno aplica, queja administrativa produce nivel 5, de lo contrario nivel 4.
- RF-06 queda resuelto para esta prueba con un flujo SSE autenticado y alertas persistidas consultables; ver [ADR-0002](../../../../docs/adr/0002-alertas-medicas-sse.md).
- El destino decidido es AKS, coherente con los manifests Kubernetes pedidos. Terraform aprovisiona infraestructura; el flujo de CI/CD construirá/publicará imágenes y desplegará manifests. El enunciado exige URL accesible en Azure, por lo que el despliegue funcional es parte del entregable. Ver [ADR-0004](../../../../docs/adr/0004-aks-terraform-y-despliegue.md).
- Spring AI y GPT-5.4-mini servido desde Azure OpenAI/Microsoft Foundry quedan seleccionados. Vector store, embeddings, formato/versionado de eventos, estrategia ante indisponibilidad de Kafka y autenticación siguen pendientes de diseño.
- La arquitectura elegida es hexagonal liviana por microservicio; sus límites y consecuencias están en [ADR-0001](../../../../docs/adr/0001-arquitectura-hexagonal.md).
- RF-05 debe concretar límites temporales y el tratamiento de pacientes sin primera consulta antes de cerrar criterios de aceptación.

## Visión

Una demostración backend pequeña y verificable donde cada clasificación y cambio de estado pueda explicarse, los médicos consulten únicamente protocolos disponibles y cualquier persona del equipo pueda reproducir los flujos desde el repositorio.
