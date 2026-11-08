# Contexto del proyecto

## Comunicación durante el trabajo

- No expongas estados internos de herramientas o coordinación como “waiting for agents”, “finished waiting”, “no agents completed yet” o “interacted with …”. Tampoco narres sondeos ni esperas rutinarias. Comparte solo avances sustantivos que ayuden al usuario a entender decisiones, hallazgos, riesgos o resultados.

Este repositorio entrega la prueba técnica de VidaCruz: dos microservicios backend independientes, Triage y Asistente Clínico, en Java reactivo. El enunciado es la fuente de requisitos; las especificaciones, contratos, decisiones y criterios de aceptación derivados deben quedar versionados en este repositorio para que cualquier persona que lo clone pueda continuar el trabajo. BMAD apoya el proceso, pero sus skills no sustituyen esa documentación.

- Para alcance y criterios de aceptación, consulta el [PRD](_bmad-output/planning-artifacts/prds/prd-PruebaTecnicaCeiba-2026-11-07/prd.md); antes de cambiar o probar clasificación, validación, SLA, auditoría o RAG, consulta también el [baseline](docs/requirements/technical-test-baseline.md), que conserva los umbrales, ejemplos y corpus exactos del enunciado.

## Límites y contratos

- Mantén Triage y Asistente Clínico como aplicaciones y unidades de despliegue independientes. Usa Kafka para la integración requerida; el Asistente no debe llamar a la API de Triage para construir su vista de urgencias.
- Triage es responsable del registro, clasificación, estados, SLA, escalamiento, auditoría y reportes. El Asistente es responsable de consultas RAG y de su vista de pacientes activos.
- Integra ambos servicios mediante Kafka en el topic `triaje.eventos`, con los eventos `paciente_registrado`, `paciente_escalado`, `paciente_en_consulta` y `paciente_alta`. Documenta el esquema/versionado de los mensajes.
- El Asistente reconstruye el estado de urgencias consumiendo eventos y persiste en Redis la proyección de pacientes activos por nivel. En Azure, la opción prevista es Azure Managed Redis, sujeta a región y presupuesto. No debe consultar la API de Triage para hacerlo.
- Triage no debe depender de la disponibilidad del Asistente para clasificar, registrar o gestionar pacientes. Evalúa durante el diseño el comportamiento ante indisponibilidad de Kafka; no anticipes una decisión antes de analizar las alternativas.

## Reglas de negocio que no se deben alterar

- Valida edad 0-120, nombre 3-100 caracteres, FC 20-250 bpm, sistólica 60-260 mmHg, diastólica 30-160 mmHg y menor que la sistólica, SpO2 70-100 %, temperatura 34-42 °C, dolor 0-10 y GCS 3-15. Devuelve HTTP 400 indicando el campo inválido.
- Clasifica aplicando RN-01 por gravedad: CRITICO antes que URGENTE, URGENTE antes que URGENTE_2; si no aplica ninguno, las quejas administrativas listadas son NO_URGENTE_2 y las demás NO_URGENTE. Usa E1-E6 del enunciado como resultados de referencia.
- Aplica los SLA de RN-02: CRITICO 5 min, URGENTE 10, URGENTE_2 30, NO_URGENTE 60 y NO_URGENTE_2 120.
- Permite REGISTRADO → EN_CONSULTA, REGISTRADO → ALTA y EN_CONSULTA → ALTA. Rechaza cualquier otra transición con HTTP 409 y mensaje descriptivo.
- Escala una sola vez a un paciente que siga REGISTRADO al superar su SLA. No escales pacientes EN_CONSULTA o ALTA. Una repetición no puede duplicar evento ni auditoría.
- Registra auditoría no borrable para registro y para cada cambio de estado o flag: timestamp, paciente, estado anterior, estado nuevo y tipo de evento.
- El Asistente solo puede dar contenido clínico respaldado por una fuente válida del corpus. Si no hay coincidencia, responde únicamente que no existe un protocolo disponible, sin contenido clínico generado.
- RF-05 agrupa por hora y devuelve total registrado, conteo por nivel, promedio y p95 del tiempo entre registro y primera entrada a consulta. Calcula agregados con SQL avanzado en la base de datos; no cargues todos los registros a memoria.
- Implementa RF-06 mediante un flujo SSE autenticado desde Triage y una consulta de alertas persistidas para recuperar eventos tras una desconexión. Kafka sigue publicando `paciente_escalado`; no lo consideres por sí solo la alerta. Ver [ADR-0002](docs/adr/0002-alertas-medicas-sse.md).

## Restricciones técnicas

- Usa Spring Boot 3, Spring WebFlux y Project Reactor (`Mono`/`Flux`) en los dos servicios, y R2DBC para PostgreSQL. No bloquees el event loop; usa APIs reactivas de extremo a extremo.
- Usa Kafka para integración y Resilience4j Circuit Breaker sobre el proveedor LLM. Si el LLM no está disponible, responde HTTP 503 con un mensaje claro; Triage debe seguir funcionando sin depender del Asistente.
- Implementa RAG con Spring AI. El diseño elige `SimpleVectorStore` en memoria y `text-embedding-3-small` para el corpus fijo inicial; usa OpenAI GPT-5.4-mini servido por Azure OpenAI/Microsoft Foundry para generación. Configura endpoint, deployment y credenciales por entorno. Aísla las APIs síncronas de embedding/búsqueda fuera del event loop. Las pruebas deben sustituir modelo y embeddings por dobles. Ver [ADR-0003](docs/adr/0003-spring-ai-y-modelo-openai.md) y el [diseño global](_bmad-output/planning-artifacts/architecture/solution-design-PruebaTecnicaCeiba.md).
- No incluyas credenciales en código ni en Git. Obtén la API key del LLM mediante variable de entorno. Protege los endpoints con JWT o API key, documentando la elección, y valida entradas.
- Añade health/readiness probes, logs estructurados con correlación y métricas Micrometer para clasificaciones por nivel, escalamientos y latencia del LLM.
- No agregues frontend: la prueba solicita APIs y eventos backend.

## Verificación y entrega

- Automatiza pruebas unitarias y de integración para clasificación E1-E6, transiciones válidas e inválidas, escalamiento idempotente, citas RAG y ausencia de alucinación cuando no hay coincidencia. Usa Testcontainers para PostgreSQL/Kafka si aporta valor al entorno disponible.
- Incluye un `docker-compose` que levante con un solo comando PostgreSQL, Kafka (o equivalente), Redis y ambos servicios.
- Despliega en AKS y entrega manifests Kubernetes con Deployments, Services y probes, IaC con Terraform y la URL de las APIs accesibles en Azure. Terraform aprovisiona infraestructura; la canalización construye/publica imágenes y aplica los manifests. Ver [ADR-0004](docs/adr/0004-aks-terraform-y-despliegue.md).
- El README debe explicar arquitectura con diagrama, tecnologías y decisiones justificadas, y ejecución local con un solo comando. Incluye al menos dos ADR que documenten decisiones evaluadas durante el análisis/diseño y queden listos antes de iniciar el build; no escribas ADR para opciones aún no analizadas.
- Versiona las especificaciones y decisiones de producto/arquitectura necesarias para entender cada cambio junto con el código correspondiente. Mantén commits progresivos con entregables funcionales.
- No inventes supuestos clínicos silenciosamente: registra las ambigüedades y su resolución en especificaciones o ADR.
- Usa arquitectura hexagonal liviana en cada microservicio, manteniendo despliegues y datos separados; aplica puertos/adaptadores donde delimiten IO externo. Ver [ADR-0001](docs/adr/0001-arquitectura-hexagonal.md).

## Agentes personalizados

- Sofia (`bmad-agent-po`) es la Product Owner personalizada del proyecto; sigue sus instrucciones en [.agents/skills/bmad-agent-po/SKILL.md](.agents/skills/bmad-agent-po/SKILL.md).
