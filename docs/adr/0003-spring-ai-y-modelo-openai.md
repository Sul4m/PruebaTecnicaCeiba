# ADR-0003: Spring AI con OpenAI GPT-5.4-mini en Azure

- Estado: Aceptada
- Fecha: 2026-11-07
- Alcance: Servicio Asistente Clínico, generación RAG

## Contexto

RF-04 requiere RAG sobre el corpus proporcionado, citas de protocolo y abstención cuando no haya coincidencia. El stack del proyecto es Spring Boot 3.5; el enunciado permite Spring AI o LangChain4j. El usuario decidió Spring AI y pidió escoger un modelo OpenAI.

## Decisión

Usar Spring AI de la línea 1.x compatible con Spring Boot 3.5 y desplegar `gpt-5.4-mini` mediante Azure OpenAI/Microsoft Foundry. Usar `text-embedding-3-small` para embeddings y `SimpleVectorStore` en memoria porque el corpus inicial es pequeño e inmutable y se reconstruye desde archivos versionados al arrancar. La credencial, endpoint y deployment names se inyectan desde el entorno o Azure Key Vault; no usar credenciales de ChatGPT ni incluir secretos en Git. Las llamadas se integran de manera reactiva mediante streaming/composición Reactor y Resilience4j. El modelo redacta solo con contexto recuperado; la aplicación aplica un umbral de relevancia configurable, controla abstención y construye las fuentes desde metadata del corpus.

## Consecuencias

- Mantiene el proveedor de generación dentro del entorno de despliegue Azure y usa la integración de Spring AI disponible para Boot 3.x.
- `SimpleVectorStore` no es persistente ni distribuido; es adecuado solo mientras el corpus sea fijo y quepa en memoria. Si el corpus crece o cambia en ejecución, revisar y reemplazar el vector store por uno persistente administrado.
- La prueba de embeddings/RAG debe cubrir las tres preguntas del baseline y calibrar el umbral de similitud; no hay valor universal correcto.
- En Spring AI 1.0, `EmbeddingModel`/`VectorStore` usan métodos síncronos. La implementación debe envolver la consulta de embedding y la búsqueda en un adaptador con `boundedElastic` y concurrencia acotada; la generación mantiene `ChatClient.stream()` reactivo. No invocar APIs síncronas en el event loop.
- `gpt-5.4-mini` figura como modelo GA en Microsoft Foundry; la disponibilidad concreta depende de región y cuota. La selección se debe validar con las tres consultas RAG obligatorias antes de cerrar implementación.
- GPT-5.4-mini es un modelo de generación, no un vector store ni una garantía de veracidad clínica. Citas, validación de pertenencia al corpus y abstención deben resolverse en la aplicación.
- Una suscripción de ChatGPT no sustituye el acceso de API. Azure OpenAI se factura/configura en Azure.

## Alternativas descartadas

- LangChain4j: no se adopta porque el proyecto eligió Spring AI, que encaja con el stack Spring existente.
- OpenAI API directa: se prefiere el endpoint de Azure para alinear configuración, secretos y facturación con el destino de despliegue.
- Modelos en preview o proveedores adicionales: añaden variabilidad sin ser necesarios para el alcance de la prueba.

## Fuentes verificadas

- [Spring AI 1.0: compatibilidad con Spring Boot 3.4 y 3.5](https://docs.spring.io/spring-ai/reference/1.0/getting-started.html)
- [Spring AI: integración Azure OpenAI y configuración por entorno](https://docs.spring.io/spring-ai/reference/1.0/api/chat/azure-openai-chat.html)
- [Microsoft Foundry: catálogo y capacidades de GPT-5.4-mini](https://learn.microsoft.com/en-us/azure/ai-foundry/foundry-models/concepts/models?view=azure-node-latest)
- [Microsoft Foundry: ciclo de vida y estado GA de GPT-5.4-mini](https://learn.microsoft.com/en-us/azure/foundry/openai/concepts/model-retirement-schedule?view=foundry-classic)
- [Spring AI 1.0: vector stores](https://docs.spring.io/spring-ai/reference/1.0/api/vectordbs.html)
- [Microsoft Foundry: catálogo de modelos vendidos por Azure](https://learn.microsoft.com/en-us/azure/foundry/foundry-models/concepts/models-sold-directly-by-azure)
