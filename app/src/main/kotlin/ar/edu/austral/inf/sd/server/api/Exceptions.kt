package ar.edu.austral.inf.sd.server.api

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.ConstraintViolationException


sealed class ApiException(msg: String, val code: Int) : Exception(msg)

class TimeOutException(msg: String) : ApiException(msg, HttpStatus.GATEWAY_TIMEOUT.value())
class InternalErrorException(msg: String) : ApiException(msg, HttpStatus.INTERNAL_SERVER_ERROR.value())
class NoServiceAvailableException(msg: String):ApiException(msg, HttpStatus.SERVICE_UNAVAILABLE.value())
class NotFoundException(msg: String, code: Int = HttpStatus.NOT_FOUND.value()) : ApiException(msg, code)
class BadRequestException(msg: String) : ApiException(msg, HttpStatus.BAD_REQUEST.value())
class UnauthorizedException(msg: String) : ApiException(msg, HttpStatus.UNAUTHORIZED.value())





@ControllerAdvice
class DefaultExceptionHandler {

    @ExceptionHandler(value = [ApiException::class])
    fun onApiException(ex: ApiException, response: HttpServletResponse): Unit =
        response.sendError(ex.code, ex.message)

    @ExceptionHandler(value = [NotImplementedError::class])
    fun onNotImplemented(ex: NotImplementedError, response: HttpServletResponse): Unit =
        response.sendError(HttpStatus.NOT_IMPLEMENTED.value())

    @ExceptionHandler(value = [ConstraintViolationException::class])
    fun onConstraintViolation(ex: ConstraintViolationException, response: HttpServletResponse): Unit =
        response.sendError(HttpStatus.BAD_REQUEST.value(), ex.constraintViolations.joinToString(", ") { it.message })
}
