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
4. El Asistente consume eventos Kafka y mantiene en Redis la proyección de pacientes activos por nivel sin consultar la API de Triage. Para RAG usa Spring AI, `SimpleVectorStore` y `text-embedding-3-small`; GPT-5.4-mini servido desde Azure OpenAI/Microsoft Foundry redacta con fragmentos recuperados y cita protocolo/sección, o se abstiene.
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

No. Reconstruye esa vista consumiendo el topic `triaje.eventos` y persiste la proyección en Redis. Los eventos incluyen un snapshot mínimo completo, versión por paciente e idempotencia para mantener conteos correctos; el servicio no llama a Triage.

### ¿Esto está listo para usarse con pacientes reales?

No se puede afirmar eso a partir del enunciado. El alcance es una prueba técnica; el corpus y reglas provistos no constituyen evidencia de validación clínica, autorización regulatoria ni preparación operativa para producción.

### ¿Qué pasa con la seguridad y los datos personales?

La prueba exige autenticación con JWT o API key, validación de entradas y secretos fuera del código. El diseño elige una API key independiente por servicio, secretos en variables de entorno localmente y Azure Key Vault con Workload ID en AKS. Esto es suficiente para la demostración; controles de privacidad, retención y entorno asistencial siguen fuera de cualquier afirmación de producción.

### ¿Cómo se entera el equipo médico de un escalamiento?

Triage ofrece un flujo SSE autenticado para alertas y un endpoint de consulta para recuperar las alertas persistidas tras una desconexión. Además, publica `paciente_escalado` en Kafka para la integración requerida. La decisión se justifica en un ADR global.

---

## Preguntas frecuentes internas

### ¿El enunciado es suficiente para empezar a implementar sin análisis adicional?

El enunciado delimita las capacidades y reglas; las decisiones necesarias para diseñar antes del build ya están documentadas en el diseño global y ADR. Esto incluye arquitectura hexagonal, SSE persistido para RF-06, AKS/Terraform, API key, contrato Kafka versionado con outbox, recuperación/idempotencia de Redis, estrategia RAG y semántica RF-05. El contrato OpenAPI detallado y parámetros ajustables de operación se concretarán durante planificación/implementación sin alterar esas decisiones.

### ¿Qué se desplegará y cómo?

La entrega incluye una URL de las APIs desplegadas y accesibles en Azure, además de manifests Kubernetes e IaC. Se eligió AKS para desplegar los manifests y Terraform para aprovisionar infraestructura. El pipeline de CI/CD construirá y publicará imágenes y aplicará los manifests. Los recursos de esta prueba vivirán en un grupo dedicado para eliminarlo tras la evaluación; el script de limpieza será local e ignorado por Git. Ver [ADR-0004](../../docs/adr/0004-aks-terraform-y-despliegue.md).

### ¿Qué modelo de IA utiliza el prototipo?

OpenAI GPT-5.4-mini servido desde Azure OpenAI/Microsoft Foundry, con `text-embedding-3-small` y `SimpleVectorStore` para el corpus fijo de la prueba. La aplicación valida relevancia, limita las fuentes a metadata del corpus y se abstiene ante consultas sin coincidencia. Esta selección no acredita calidad clínica; se verificará con los casos del enunciado. Ver [ADR-0003](../../docs/adr/0003-spring-ai-y-modelo-openai.md).

### ¿Qué puede invalidar el reporte de operación aunque la API responda?

RF-05 se agrupa por hora UTC de registro; usa rango `[desde,hasta)`. Los totales incluyen todos los pacientes del periodo; quienes aún no han entrado a consulta se excluyen solo del promedio y p95 de espera. PostgreSQL calcula promedio y `percentile_cont(0.95)` sin cargar pacientes en memoria. Esta semántica quedó fijada en el diseño.

### ¿Qué riesgo tiene el escalamiento automático?

Debe afectar solo pacientes aún `REGISTRADO` que excedan su SLA; no puede escalar pacientes `EN_CONSULTA` o `ALTA`, y repetidos intentos no pueden duplicar evento ni auditoría. Una actualización condicional transaccional decide el ganador y escribe auditoría, alerta y outbox juntos; el relay publica con reintentos y la idempotencia cubre duplicados. Los casos concurrentes y de recuperación están definidos como criterios de prueba.

### ¿Qué demuestra la prueba sobre seguridad clínica del RAG?

Demuestra comportamiento de software frente al corpus entregado: citas válidas y abstención cuando no hay coincidencia, incluido el caso de diabetes tipo 2. No demuestra que el corpus esté completo, que la recuperación sea clínicamente adecuada para otros casos ni que el sistema sea seguro para uso real.

### ¿Cabe todo en el plazo de tres días?

El plazo está fijado por el enunciado y el alcance es amplio: dos servicios, Kafka, datos reactivos, reportes SQL, RAG, pruebas, infraestructura y Azure. El análisis no garantiza el cumplimiento; la planificación debe priorizar cada requisito obligatorio y reservar tiempo para integración y despliegue.

### ¿Se decidió la arquitectura o se redactaron ADR?

Sí. Se decidió arquitectura hexagonal liviana por microservicio y se documentaron decisiones globales para alertas, IA y despliegue. Las decisiones restantes se documentarán al cerrar diseño, antes de implementar las partes correspondientes.

---

## Veredicto

**El concepto está bien delimitado para continuar a planificación**, porque el enunciado incluye usuarios, capacidades, reglas de negocio, datos de referencia y criterios mínimos de evaluación. El Product Brief y este PRFAQ reinterpretan ese alcance como una iniciativa hipotética; no añaden una promesa de producto comercial.

**Diseño técnico listo para planificación:** las decisiones de arquitectura, integración, alertas, seguridad, reporte, RAG y despliegue están documentadas. La planificación debe convertirlas en historias y criterios verificables. Terraform recibirá la suscripción y región elegidas en la cuenta del usuario; el entorno se elimina después de la evaluación.

**Riesgo principal:** tratar el prototipo como sistema clínico real. El enunciado no aporta validación clínica, regulatoria, privacidad detallada ni aprobación operativa. Mantener la demostración dentro del alcance de la prueba y dejar esa limitación explícita.

<!-- coaching-notes-stage-1 -->
- Tipo de concepto: prototipo interno hipotético para evaluación técnica; no es lanzamiento comercial.
- Fuente primaria: enunciado suministrado por el usuario. No se usaron estadísticas de mercado ni afirmaciones competitivas.
- No se hizo brainstorming: la necesidad, usuarios y solución base están dados por la prueba.
- Decisiones tomadas en análisis/diseño: arquitectura hexagonal liviana, alertas SSE persistidas, Spring AI con GPT-5.4-mini vía Azure OpenAI/Microsoft Foundry, `SimpleVectorStore` y `text-embedding-3-small`, contrato Kafka con outbox e idempotencia, API key por servicio, semántica RF-05, AKS y Terraform.
- Parámetros del despliegue temporal: suscripción y región de la cuenta Azure del usuario, recibidas por Terraform. El grupo de recursos se eliminará tras la evaluación mediante un script local ignorado por Git.
<!-- coaching-notes-stage-2 -->
- Se evitó presentar el servicio como desplegado, aprobado clínicamente o listo para uso real.
- No se atribuyen testimonios a personas reales; las citas son texto hipotético de framing.
<!-- coaching-notes-stage-3 -->
- Preguntas de usuarios expusieron la necesidad de distinguir una prueba funcional de autorización de uso clínico real.
- La respuesta a seguridad y privacidad permanece incompleta en el enunciado; no se afirman controles ausentes.
<!-- coaching-notes-stage-4 -->
- Riesgos a verificar en planificación/implementación: compatibilidad del servicio Kafka elegido con el contrato, concurrencia del scheduler y calibración del umbral RAG.
- El plazo de tres días requiere priorización explícita en sprint planning.
<!-- coaching-notes-stage-5 -->
- Evaluación: concepto y diseño suficientemente definidos para pasar a desglose de historias y planificación antes del build.
