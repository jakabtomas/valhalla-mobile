package com.valhalla.valhalla.http

/**
 * Synchronous HTTP transport used by Valhalla to obtain remote graph tiles.
 *
 * Valhalla's native `tile_getter_t` API is synchronous, so implementations must not dispatch back
 * to the calling thread. The routing call already runs on the caller's thread; applications should
 * therefore invoke routing away from the main thread.
 *
 * Implementations may resolve requests from a memory cache, a disk cache, an installed offline
 * package, or a network source. This makes the transport suitable for applications that want one
 * routing engine with multiple data providers.
 */
interface ValhallaHttpClient {

  /**
   * Fetches bytes from [url].
   *
   * When [rangeSize] is greater than zero, the response must contain exactly [rangeSize] bytes
   * beginning at [rangeOffset], and its HTTP status must be 206. Implementations must not
   * transparently return the entire resource for a range request.
   */
  fun get(url: String, rangeOffset: Long = 0, rangeSize: Long = 0): ValhallaHttpResponse

  /**
   * Fetches metadata from [url].
   *
   * [headerMask] uses the bit mask defined by Valhalla's native `tile_getter_t`. Bit 0 requests the
   * `Last-Modified` timestamp.
   */
  fun head(url: String, headerMask: Int): ValhallaHttpResponse
}

/** Stable categories for failures crossing the Kotlin/JNI boundary. */
enum class ValhallaHttpFailureKind(internal val nativeCode: Int) {
  INVALID_REQUEST(1),
  NETWORK(2),
  TIMEOUT(3),
  HTTP_STATUS(4),
  INVALID_RESPONSE(5),
  CANCELLED(6),
  CALLBACK_EXCEPTION(7),
  INTERNAL(8),
  MISSING_COVERAGE(9);

  internal companion object {
    fun fromNativeCode(code: Int): ValhallaHttpFailureKind? =
        entries.firstOrNull { it.nativeCode == code }
  }
}

/**
 * Explicit no-network fallback for offline-only routing.
 *
 * Applications normally inject a provider that checks cache and installed packages first. When that
 * provider has no matching tile, it can return [ValhallaHttpFailureKind.MISSING_COVERAGE]. This
 * implementation is useful when no remote provider is installed at all.
 */
object OfflineOnlyValhallaHttpClient : ValhallaHttpClient {
  override fun get(url: String, rangeOffset: Long, rangeSize: Long): ValhallaHttpResponse =
      missingCoverage()

  override fun head(url: String, headerMask: Int): ValhallaHttpResponse = missingCoverage()

  private fun missingCoverage() =
      ValhallaHttpResponse.failure(
          ValhallaHttpFailureKind.MISSING_COVERAGE,
          "The requested routing tile is not installed for offline use.")
}

/**
 * Result returned by [ValhallaHttpClient].
 *
 * Use [success] and [failure] instead of constructing values directly. The public bridge fields are
 * intentionally stable because native JNI code reads them by name.
 */
class ValhallaHttpResponse
private constructor(
    @JvmField val statusCode: Long,
    @JvmField val body: ByteArray,
    @JvmField val lastModifiedEpochSeconds: Long,
    @JvmField val failureCode: Int,
    @JvmField val failureMessage: String?
) {

  val failureKind: ValhallaHttpFailureKind?
    get() = ValhallaHttpFailureKind.fromNativeCode(failureCode)

  companion object {
    /** Creates a successful HTTP response. */
    @JvmStatic
    @JvmOverloads
    fun success(
        statusCode: Long,
        body: ByteArray = byteArrayOf(),
        lastModifiedEpochSeconds: Long = 0
    ): ValhallaHttpResponse =
        ValhallaHttpResponse(
            statusCode = statusCode,
            body = body,
            lastModifiedEpochSeconds = lastModifiedEpochSeconds,
            failureCode = 0,
            failureMessage = null)

    /** Creates a typed transport failure. */
    @JvmStatic
    @JvmOverloads
    fun failure(
        kind: ValhallaHttpFailureKind,
        message: String,
        statusCode: Long = 0
    ): ValhallaHttpResponse =
        ValhallaHttpResponse(
            statusCode = statusCode,
            body = byteArrayOf(),
            lastModifiedEpochSeconds = 0,
            failureCode = kind.nativeCode,
            failureMessage = message)
  }
}
