package ar.edu.austral.inf.sd.server.api

import ar.edu.austral.inf.sd.server.model.RegisterResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import org.springframework.web.bind.annotation.*
import org.springframework.validation.annotation.Validated
import org.springframework.beans.factory.annotation.Autowired

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

@RestController
@Validated
@RequestMapping("\${api.base-path:}")
class RegisterNodeApiController(@Autowired(required = true) val service: RegisterNodeApiService) {

    @RequestMapping(
        method = [RequestMethod.POST],
        value = ["/register-node"],
        produces = ["application/json"]
    )
    fun registerNode(
        @Valid @RequestParam(value = "host", required = true) @NotBlank host: String?,
        @Valid @RequestParam(value = "port", required = true) @NotNull @Positive port: Int?,
        @Valid @RequestParam(value = "uuid", required = true) @NotNull uuid: java.util.UUID?,
        @Valid @RequestParam(value = "salt", required = true) @NotBlank salt: String?,
        @Valid @RequestParam(value = "name", required = true) @NotBlank name: String?
    ): ResponseEntity<RegisterResponse> {
        return service.registerNode(host, port, uuid, salt, name)
    }
}
