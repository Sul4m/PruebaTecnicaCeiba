package com.vidacruz.clinical_assistant_service.domain;

public enum ResultadoProyeccion {
    /** Version mayor aplicada (la siguiente a la materializada, o la primera del paciente). */
    APLICADO,
    /** Version mayor aplicada con hueco de versiones: se aplico el snapshot completo. */
    APLICADO_CON_SALTO,
    /** eventId ya procesado: sin cambios. */
    DUPLICADO,
    /** Version menor o igual a la materializada: sin cambios. */
    VERSION_ANTIGUA
}
