# ADR-0008: Autenticación con API key independiente por servicio

- Estado: Aceptada
- Fecha: 2026-11-08
- Alcance: Triage y Asistente Clínico

## Contexto

El enunciado exige autenticación JWT o API key (decisión por documentar) y que las credenciales no estén en el código. No hay emisor de identidad, usuarios ni roles definidos, y no hay frontend.

## Decisión

Cada servicio valida la cabecera `X-API-Key` con un filtro WebFlux Security. Las claves son distintas (`TRIAGE_API_KEY`, `ASISTENTE_API_KEY`), se leen solo del entorno y no tienen valor por defecto; el Asistente no arranca sin la suya. Sin clave o con una incorrecta la respuesta es 401 con cuerpo vacío. `/actuator/health/**` es público porque no revela datos. En AKS las claves y la del proveedor LLM vendrán de Azure Key Vault.

## Consecuencias

- Simple y suficiente para la demo acotada; no representa identidad individual ni autorización granular.
- Comprometer una clave no compromete el otro servicio.
- Rotar claves requiere reiniciar el servicio con el nuevo valor, sin rebuild de imagen.
- Los clientes de navegador (`EventSource`) no pueden enviar cabeceras propias; para el stream SSE usar `fetch` con cabeceras (ver [`docs/alertas.md`](../alertas.md)).

## Alternativa descartada

JWT: exige un emisor de identidad, gestión de claves y roles que el alcance de la prueba no necesita.
