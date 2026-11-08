# ADR-0004: Despliegue de APIs en AKS con Terraform

- Estado: Aceptada
- Fecha: 2026-11-07
- Alcance: infraestructura y publicación de los dos servicios

## Contexto

Los entregables obligatorios incluyen una URL de la aplicación desplegada en Azure con APIs accesibles, manifests de Kubernetes e infraestructura como código. El enunciado permite AKS o Azure Container Apps, pero también pide manifests `Deployment`, `Service` y probes. La evaluación considera infraestructura y despliegue.

## Decisión

Desplegar ambas aplicaciones en Azure Kubernetes Service (AKS), administrar recursos de Azure con Terraform y versionar los manifests Kubernetes. Usar una canalización de CI/CD del repositorio para construir imágenes, publicarlas en Azure Container Registry y aplicar los manifests al clúster. La canalización publicará/verificará las URLs de las APIs. Terraform provisiona infraestructura; no sustituye el build ni el despliegue de las aplicaciones.

Todos los recursos de la prueba se crean en un grupo de recursos dedicado y etiquetado `project=PruebaTecnicaCeiba`, sin incorporar recursos compartidos. Después de la evaluación se elimina el grupo completo con el script local ignorado por Git; el usuario revisa el inventario y confirma escribiendo el nombre exacto del grupo. La ejecución de limpieza también elimina los servicios gestionados del ejercicio y no forma parte de CI/CD.

## Consecuencias

- Los manifests requeridos son los artefactos que despliegan los servicios en el destino elegido.
- Terraform deja reproducibles los recursos de nube y permite declarar infraestructura en el repositorio.
- AKS requiere configurar acceso al clúster, red, secretos y un backend para el estado Terraform. El entorno se crea en la suscripción del usuario y se elimina al concluir la evaluación.
- El grupo dedicado permite borrar el entorno temporal después de la revisión técnica sin depender de conservar infraestructura o estado Terraform.
- URL pública implica limitar exposición a las rutas necesarias, aplicar autenticación y no publicar credenciales ni servicios internos.
- Terraform parametriza suscripción, región y tamaño necesario del clúster para la prueba; estos valores se pasan al aplicar en la cuenta del usuario y no son bloqueos de diseño. No se crean recursos Azure durante esta decisión documental.

## Alternativa descartada

Azure Container Apps simplificaría parte de la operación, pero sus manifiestos de aplicación no serían los `Deployment`/`Service` Kubernetes solicitados. AKS alinea mejor el destino ejecutable con los artefactos que debe evaluar el enunciado.

## Fuente verificada

- [Microsoft Learn: despliegue de AKS con Terraform](https://learn.microsoft.com/en-us/azure/aks/learn/quick-kubernetes-deploy-terraform)
