# ADR-0001: Arquitectura hexagonal liviana por microservicio

- Estado: Aceptada
- Fecha: 2026-11-07
- Alcance: repositorio y ambos microservicios

## Contexto

La prueba exige dos aplicaciones independientes, integración HTTP/Kafka, persistencia reactiva y una integración con un proveedor LLM. La arquitectura interna es de libre elección. Los adaptadores externos y los casos de uso tienen ritmos de cambio y necesidades de prueba diferentes.

## Decisión

Cada microservicio aplicará arquitectura hexagonal liviana: dominio y casos de uso en el centro; entradas HTTP, scheduler y Kafka como adaptadores de entrada; PostgreSQL/R2DBC, Kafka, Redis/vector store y proveedor LLM como adaptadores de salida detrás de puertos. Cada servicio conserva su propio proceso de despliegue y propiedad de datos. El repositorio puede alojar ambos servicios, pero no se comparte lógica de dominio ni acceso directo a la base de datos del otro.

## Consecuencias

- Los casos de uso pueden probarse sin levantar infraestructura externa.
- Las llamadas de infraestructura se sustituyen por implementaciones reactivas; no se introducen bloqueos en el event loop.
- Se evita imponer capas/clases ceremoniales cuando no añaden una frontera útil.
- Los contratos entre servicios se publican mediante Kafka; el asistente no consulta la API de Triage para reconstruir urgencias.

## Alternativa descartada

Una arquitectura por capas tradicional también cumpliría, pero deja menos explícita la dirección de dependencias y el aislamiento de las numerosas integraciones externas requeridas.
