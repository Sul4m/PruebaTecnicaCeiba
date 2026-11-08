package com.vidacruz.triage_service.infrastructure.web;

import com.vidacruz.triage_service.application.ChangePatientStatusUseCase;
import com.vidacruz.triage_service.application.GetPatientUseCase;
import com.vidacruz.triage_service.application.InvalidStatusTransitionException;
import com.vidacruz.triage_service.application.PatientNotFoundException;
import com.vidacruz.triage_service.application.RegisterPatientCommand;
import com.vidacruz.triage_service.application.RegisterPatientUseCase;
import com.vidacruz.triage_service.domain.VitalSigns;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/triage/pacientes")
class PatientController {

    private static final Logger log = LoggerFactory.getLogger(PatientController.class);

    private final RegisterPatientUseCase registerPatient;
    private final GetPatientUseCase getPatient;
    private final ChangePatientStatusUseCase changeStatus;
    private final Clock clock;

    PatientController(RegisterPatientUseCase registerPatient, GetPatientUseCase getPatient,
            ChangePatientStatusUseCase changeStatus, Clock clock) {
        this.registerPatient = registerPatient;
        this.getPatient = getPatient;
        this.changeStatus = changeStatus;
        this.clock = clock;
    }

    @GetMapping("/{id}")
    Mono<PacienteDetalleResponse> get(@PathVariable("id") UUID id) {
        return getPatient.get(id)
                // Trazabilidad de acceso: solo id y estado, sin datos clínicos.
                .doOnNext(p -> log.info("Paciente consultado id={} estado={}", p.id(), p.status()))
                .doOnError(PatientNotFoundException.class, e -> log.info("Paciente no encontrado id={}", id))
                .map(p -> PacienteDetalleResponse.from(p, clock.instant()));
    }

    @PatchMapping("/{id}/estado")
    Mono<PacienteDetalleResponse> changeStatus(@PathVariable("id") UUID id,
            @Valid @RequestBody CambiarEstadoRequest request) {
        return changeStatus.change(id, request.estado())
                .doOnError(InvalidStatusTransitionException.class,
                        e -> log.info("Transición rechazada id={} actual={} solicitado={}", id, e.current(),
                                e.requested()))
                .doOnNext(c -> log.info("Estado de paciente cambiado id={} anterior={} nuevo={}", c.patient().id(),
                        c.previous(), c.patient().status()))
                .map(c -> PacienteDetalleResponse.from(c.patient(), clock.instant()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Mono<PacienteRegistradoResponse> register(@Valid @RequestBody RegistrarPacienteRequest request) {
        SignosVitalesRequest s = request.signosVitales();
        VitalSigns vitals = new VitalSigns(s.frecuenciaCardiaca(), s.presionSistolica(), s.presionDiastolica(),
                s.spo2(), s.temperatura(), s.dolor(), s.gcs());
        RegisterPatientCommand command = new RegisterPatientCommand(request.nombreCompleto(), request.edad(),
                request.quejaPrincipal(), vitals);
        return registerPatient.register(command)
                // Solo id y nivel: sin nombre, signos vitales ni queja.
                .doOnNext(p -> log.info("Paciente registrado id={} nivel={}", p.id(), p.level()))
                .map(PacienteRegistradoResponse::from);
    }
}
