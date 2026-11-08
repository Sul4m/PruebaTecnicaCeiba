---
title: "PRFAQ: PruebaTecnicaCeiba - triaje digital VidaCruz"
status: "complete"
created: "2026-11-07"
updated: "2026-11-07"
stage: "5"
inputs:
  - "Enunciado de la prueba técnica Dev. Webflux, fechado 2026-10-29 (PDF fuente externo al repositorio)"
concept_type: "prototipo interno hipotético para una prueba técnica"
---

# VidaCruz propone un backend de triaje trazable y consulta acotada de protocolos

## El personal de urgencias obtiene clasificación consistente, seguimiento de tiempos y respuestas clínicas con fuentes visibles

**VidaCruz — escenario hipotético de la prueba técnica; fecha de anuncio por definir.** La iniciativa plantea un backend para registrar pacientes, aplicar reglas explícitas de triaje y dar seguimiento al tiempo hasta consulta. También propone una consulta de protocolos internos que muestra de dónde proviene cada respuesta.

En el escenario de la prueba, la clasificación se anota manualmente y puede variar; no siempre queda claro qué ocurrió con cada paciente ni cuánto esperó. Mientras tanto, el personal médico necesita consultar protocolos internos durante la atención.

La propuesta digitaliza el flujo solicitado para evaluación: niveles y SLA definidos por reglas, transiciones permitidas, auditoría, escalamiento y un asistente que responde únicamente cuando encuentra respaldo en el corpus dado. La iniciativa no se presenta como producto aprobado para uso asistencial ni como sustituto del criterio clínico.

> “Queremos que cada paso del flujo pueda verificarse y que una respuesta clínica muestre su respaldo o se abstenga cuando no lo encuentra.”
> — Mensaje hipotético del equipo proponente

### Cómo funcionaría

1. Enfermería registra nombre, edad, signos vitales y queja principal mediante la API de Triage.
2. El servicio valida los datos, clasifica según la regla de negocio, asigna SLA y publica el evento de registro.
3. El flujo permite las transiciones clínicas especificadas; el sistema guarda auditoría y escala una sola vez a quien exceda su SLA mientras siga `REGISTRADO`.
4. El Asistente consume eventos para mantener el conteo de pacientes activos por nivel. El personal médico puede consultar protocolos: Spring AI usa GPT-5.4-mini servido desde Azure OpenAI/Microsoft Foundry y la respuesta incluye protocolo y sección, o indica que no hay uno disponible.
5. Si el proveedor LLM falla, la consulta del Asistente devuelve HTTP 503 con mensaje claro; la operación de Triage no depende del Asistente.
6. Cuando Triage escala un paciente, publica el evento Kafka y emite una alerta por SSE. Las alertas quedan persistidas y pueden consultarse después de una desconexión.

> “Puedo revisar qué protocolo respalda la respuesta; si el sistema no encuentra uno, prefiero que lo diga claramente.”
> — Voz hipotética de un médico usuario; frase de diseño, no testimonio real

### Cómo probarlo

La primera versión se verifica como software de evaluación: pruebas de clasificación E1-E6, estados, idempotencia, citas y abstención del RAG; una ejecución local con un comando; y las APIs desplegadas y accesibles en Azure mediante AKS, manifests Kubernetes y Terraform.

---

## Preguntas frecuentes de usuarios

### ¿El sistema sustituye la decisión de enfermería o del médico?

No es el objetivo descrito por la prueba. El servicio aplica reglas de clasificación especificadas y el asistente permite consultar el corpus; la prueba no autoriza diagnóstico autónomo ni sustitución del juicio clínico. La supervisión humana deberá mantenerse como supuesto explícito del prototipo.

### ¿Cómo evita inventar recomendaciones clínicas?

Toda respuesta clínica debe incluir una fuente válida del corpus: protocolo y sección. Si la recuperación no encuentra coincidencia relevante, debe devolver exclusivamente el aviso de que no hay protocolo disponible, sin generar contenido clínico.

### ¿Qué pasa si el modelo de lenguaje no está disponible?

El Asistente responde HTTP 503 con un mensaje claro. La clasificación, registro y gestión de pacientes en Triage no deben depender de que el Asistente esté disponible.

### ¿El Asistente llama al servicio Triage para mostrar la urgencia actual?

No. Debe reconstruir esa vista consumiendo el topic `triaje.eventos` y persistir el conteo de pacientes activos por nivel en Redis o una base NoSQL.

### ¿Esto está listo para usarse con pacientes reales?

No se puede afirmar eso a partir del enunciado. El alcance es una prueba técnica; el corpus y reglas provistos no constituyen evidencia de validación clínica, autorización regulatoria ni preparación operativa para producción.

### ¿Qué pasa con la seguridad y los datos personales?

La prueba exige autenticación con JWT o API key, validación de entradas y secretos fuera del código. El mecanismo concreto, los controles de privacidad, la retención y el entorno asistencial no están definidos y deben quedar fuera de cualquier afirmación de producción.

### ¿Cómo se entera el equipo médico de un escalamiento?

Triage ofrece un flujo SSE autenticado para alertas y un endpoint de consulta para recuperar las alertas persistidas tras una desconexión. Además, publica `paciente_escalado` en Kafka para la integración requerida. La decisión se justifica en un ADR global.

---

## Preguntas frecuentes internas

### ¿El enunciado es suficiente para empezar a implementar sin análisis adicional?

Es suficiente para delimitar capacidades y muchas reglas, pero no para resolver todas las decisiones. Ya se eligieron canal RF-06, arquitectura, Spring AI/modelo y destino AKS/Terraform. Siguen abiertos el formato de eventos, políticas de reintento/reprocesamiento, autenticación, vector store/embeddings y varios detalles del reporte. Esos vacíos deben resolverse durante diseño; no se rellenan silenciosamente.

### ¿Qué se desplegará y cómo?

La entrega incluye una URL de las APIs desplegadas y accesibles en Azure, además de manifests Kubernetes e IaC. Se eligió AKS para desplegar los manifests y Terraform para aprovisionar infraestructura. El pipeline de CI/CD construirá y publicará imágenes y aplicará los manifests. Ver ADR global de despliegue.

### ¿Qué modelo de IA utiliza el prototipo?

OpenAI GPT-5.4-mini servido desde Azure OpenAI/Microsoft Foundry. Se eligió un modelo compacto para las consultas RAG del corpus de la prueba; esta selección no acredita calidad clínica y las respuestas se validarán con los casos del enunciado. La API se configura con secretos en el entorno. El modelo de embeddings y el vector store quedan por decidir.

### ¿Qué puede invalidar el reporte de operación aunque la API responda?

RF-05 exige agregación horaria, total y conteos por nivel, espera promedio y p95 desde registro hasta la primera entrada a consulta, con SQL avanzado. Antes de implementar se debe acordar el límite de `desde`/`hasta`, el tratamiento de pacientes aún sin consulta y la definición exacta del percentil; no se permite cargar todos los registros en memoria para agregar.

### ¿Qué riesgo tiene el escalamiento automático?

Debe afectar solo pacientes aún `REGISTRADO` que excedan su SLA; no puede escalar pacientes `EN_CONSULTA` o `ALTA`, y repetidos intentos no pueden duplicar evento ni auditoría. La concurrencia entre cambio de estado y scheduler, y la recuperación tras reinicio, son escenarios de diseño y prueba que faltan por concretar.

### ¿Qué demuestra la prueba sobre seguridad clínica del RAG?

Demuestra comportamiento de software frente al corpus entregado: citas válidas y abstención cuando no hay coincidencia, incluido el caso de diabetes tipo 2. No demuestra que el corpus esté completo, que la recuperación sea clínicamente adecuada para otros casos ni que el sistema sea seguro para uso real.

### ¿Cabe todo en el plazo de tres días?

El plazo está fijado por el enunciado y el alcance es amplio: dos servicios, Kafka, datos reactivos, reportes SQL, RAG, pruebas, infraestructura y Azure. El análisis no garantiza el cumplimiento; la planificación debe priorizar cada requisito obligatorio y reservar tiempo para integración y despliegue.

### ¿Se decidió la arquitectura o se redactaron ADR?

Sí. Se decidió arquitectura hexagonal liviana por microservicio y se documentaron decisiones globales para alertas, IA y despliegue. Las decisiones restantes se documentarán al cerrar diseño, antes de implementar las partes correspondientes.

---

## Veredicto

**El concepto está bien delimitado para continuar a planificación**, porque el enunciado incluye usuarios, capacidades, reglas de negocio, datos de referencia y criterios mínimos de evaluación. El Product Brief y este PRFAQ reinterpretan ese alcance como una iniciativa hipotética; no añaden una promesa de producto comercial.

**Necesita más definición antes del build:** contrato y confiabilidad de eventos, límites del reporte RF-05, seguridad concreta y vector store/embeddings RAG. RF-06, arquitectura, framework/modelo de generación y destino Azure/IaC ya tienen decisiones registradas.

**Riesgo principal:** tratar el prototipo como sistema clínico real. El enunciado no aporta validación clínica, regulatoria, privacidad detallada ni aprobación operativa. Mantener la demostración dentro del alcance de la prueba y dejar esa limitación explícita.

<!-- coaching-notes-stage-1 -->
- Tipo de concepto: prototipo interno hipotético para evaluación técnica; no es lanzamiento comercial.
- Fuente primaria: enunciado suministrado por el usuario. No se usaron estadísticas de mercado ni afirmaciones competitivas.
- No se hizo brainstorming: la necesidad, usuarios y solución base están dados por la prueba.
- Decisiones tomadas tras el análisis: arquitectura hexagonal liviana, alertas SSE persistidas, Spring AI con GPT-5.4-mini vía Azure OpenAI/Microsoft Foundry, AKS y Terraform.
- Decisiones pendientes: formato/reprocesamiento Kafka, vector store/embeddings, autenticación y semántica exacta de RF-05.
<!-- coaching-notes-stage-2 -->
- Se evitó presentar el servicio como desplegado, aprobado clínicamente o listo para uso real.
- No se atribuyen testimonios a personas reales; las citas son texto hipotético de framing.
<!-- coaching-notes-stage-3 -->
- Preguntas de usuarios expusieron la necesidad de distinguir una prueba funcional de autorización de uso clínico real.
- La respuesta a seguridad y privacidad permanece incompleta en el enunciado; no se afirman controles ausentes.
<!-- coaching-notes-stage-4 -->
- Riesgos: integración y reintentos Kafka, concurrencia del scheduler, semántica de p95/fechas, límites de recuperación RAG, Azure/manifests incompatibles si se mezclan opciones.
- El plazo de tres días requiere priorización explícita en sprint planning.
<!-- coaching-notes-stage-5 -->
- Evaluación: concepto suficientemente claro para pasar a Product Requirements / especificación; no listo para build hasta cerrar requisitos ambiguos y decisiones de arquitectura.
