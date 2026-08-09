package com.valhalla.valhalla

import com.squareup.moshi.Json

data class ErrorResponse(
    val code: Int,
    val message: String,
    @param:Json(name = "error_type") val errorType: String? = null,
    val operation: String? = null,
    @param:Json(name = "failure_kind") val failureKind: String? = null,
    @param:Json(name = "http_code") val httpCode: Long? = null,
    val detail: String? = null
) {
  override fun toString(): String {
    return "ValhallaError(code=$code, $message)"
  }
}

sealed class ValhallaException(message: String? = null, cause: Throwable? = null) :
    Exception(message, cause) {
  constructor(cause: Throwable) : this(null, cause)

  /**
   * An error returned by the routing engine. See
   * [Valhalla - Internal Error](https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/#internal-error-codes-and-conditions)
   *
   * @param response
   * @constructor TODO
   */
  class Internal(response: ErrorResponse) : ValhallaException(response.toString(), null)

  /** A typed graph tile provider failure, including offline missing coverage. */
  class TileFetch(val response: ErrorResponse) : ValhallaException(response.toString(), null)

  class InvalidError : ValhallaException("Invalid error response data")

  class InvalidResponse : ValhallaException("Invalid response data")

  class NotSupported : ValhallaException("The format is not currently supported")
}
