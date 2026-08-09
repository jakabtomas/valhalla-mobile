package com.valhalla.valhalla.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

/**
 * Production HTTP transport backed by Android's platform [HttpURLConnection].
 *
 * The implementation uses bounded streaming reads and `Accept-Encoding: identity`, which are
 * required for byte offsets in remote indexed tar archives to remain valid.
 */
class UrlConnectionValhallaHttpClient
@JvmOverloads
constructor(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val maximumResponseBytes: Int = DEFAULT_MAXIMUM_RESPONSE_BYTES,
    private val userAgent: String = DEFAULT_USER_AGENT
) : ValhallaHttpClient {

  init {
    require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive." }
    require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive." }
    require(maximumResponseBytes > 0) { "maximumResponseBytes must be positive." }
    require(userAgent.isNotBlank()) { "userAgent must not be blank." }
  }

  override fun get(url: String, rangeOffset: Long, rangeSize: Long): ValhallaHttpResponse {
    if (Thread.currentThread().isInterrupted) {
      return cancelledResponse()
    }
    if (rangeOffset < 0 || rangeSize < 0) {
      return ValhallaHttpResponse.failure(
          ValhallaHttpFailureKind.INVALID_REQUEST, "Range offset and size must not be negative.")
    }
    if (rangeSize > maximumResponseBytes) {
      return ValhallaHttpResponse.failure(
          ValhallaHttpFailureKind.INVALID_REQUEST,
          "Requested range exceeds the configured response limit.")
    }
    if (rangeSize > 0 && rangeOffset > Long.MAX_VALUE - rangeSize) {
      return ValhallaHttpResponse.failure(
          ValhallaHttpFailureKind.INVALID_REQUEST, "Requested range overflows.")
    }

    return execute(url, "GET") { connection ->
      if (rangeSize > 0) {
        val inclusiveEnd = rangeOffset + rangeSize - 1
        connection.setRequestProperty("Range", "bytes=$rangeOffset-$inclusiveEnd")
      }

      val status = connection.responseCode
      if (status !in 200..299) {
        closeResponseBody(connection)
        return@execute ValhallaHttpResponse.failure(
            ValhallaHttpFailureKind.HTTP_STATUS,
            "The tile server returned HTTP $status.",
            status.toLong())
      }
      if (rangeSize > 0 && status != HttpURLConnection.HTTP_PARTIAL) {
        closeResponseBody(connection)
        return@execute ValhallaHttpResponse.failure(
            ValhallaHttpFailureKind.INVALID_RESPONSE,
            "The server did not honor the byte range request.",
            status.toLong())
      }
      if (rangeSize > 0 &&
          !matchesRequestedContentRange(
              connection.getHeaderField("Content-Range"), rangeOffset, rangeSize)) {
        closeResponseBody(connection)
        return@execute ValhallaHttpResponse.failure(
            ValhallaHttpFailureKind.INVALID_RESPONSE,
            "The server returned an invalid Content-Range header.",
            status.toLong())
      }

      val expectedSize = if (rangeSize > 0) rangeSize else null
      val contentLength = connection.contentLengthLong.takeIf { it >= 0 }
      if (expectedSize != null && contentLength != null && contentLength != expectedSize) {
        closeResponseBody(connection)
        return@execute ValhallaHttpResponse.failure(
            ValhallaHttpFailureKind.INVALID_RESPONSE,
            "The byte range response length does not match the request.",
            status.toLong())
      }
      if (contentLength != null && contentLength > maximumResponseBytes) {
        closeResponseBody(connection)
        return@execute ValhallaHttpResponse.failure(
            ValhallaHttpFailureKind.INVALID_RESPONSE,
            "The response exceeds the configured response limit.",
            status.toLong())
      }

      val maximumBytes = expectedSize?.toInt() ?: maximumResponseBytes
      val bytes =
          connection.inputStream.use { input ->
            val output = ByteArrayOutputStream(contentLength?.toInt() ?: DEFAULT_BUFFER_SIZE)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
              if (Thread.currentThread().isInterrupted) {
                return@execute cancelledResponse()
              }
              val count = input.read(buffer)
              if (count < 0) break
              if (total > maximumBytes - count) {
                return@execute ValhallaHttpResponse.failure(
                    ValhallaHttpFailureKind.INVALID_RESPONSE,
                    "The response exceeds the requested or configured size.",
                    status.toLong())
              }
              output.write(buffer, 0, count)
              total += count
            }
            output.toByteArray()
          }

      if (expectedSize != null && bytes.size.toLong() != expectedSize) {
        return@execute ValhallaHttpResponse.failure(
            ValhallaHttpFailureKind.INVALID_RESPONSE,
            "The byte range response was truncated.",
            status.toLong())
      }
      ValhallaHttpResponse.success(status.toLong(), bytes)
    }
  }

  override fun head(url: String, headerMask: Int): ValhallaHttpResponse =
      execute(url, "HEAD") { connection ->
        if (Thread.currentThread().isInterrupted) {
          return@execute cancelledResponse()
        }
        val status = connection.responseCode
        if (status !in 200..299) {
          closeResponseBody(connection)
          return@execute ValhallaHttpResponse.failure(
              ValhallaHttpFailureKind.HTTP_STATUS,
              "The tile server returned HTTP $status.",
              status.toLong())
        }
        val lastModifiedSeconds =
            if (headerMask and HEADER_LAST_MODIFIED != 0) {
              connection.lastModified.takeIf { it > 0 }?.div(MILLIS_PER_SECOND) ?: 0
            } else {
              0
            }
        closeResponseBody(connection)
        ValhallaHttpResponse.success(
            statusCode = status.toLong(), lastModifiedEpochSeconds = lastModifiedSeconds)
      }

  private inline fun execute(
      url: String,
      method: String,
      block: (HttpURLConnection) -> ValhallaHttpResponse
  ): ValhallaHttpResponse {
    if (Thread.currentThread().isInterrupted) {
      return cancelledResponse()
    }
    val parsedUrl =
        try {
          validatedUrl(url)
        } catch (_: MalformedURLException) {
          return ValhallaHttpResponse.failure(
              ValhallaHttpFailureKind.INVALID_REQUEST, "The tile URL is invalid.")
        } catch (_: IllegalArgumentException) {
          return ValhallaHttpResponse.failure(
              ValhallaHttpFailureKind.INVALID_REQUEST, "The tile URL is invalid.")
        }

    var connection: HttpURLConnection? = null
    return try {
      connection =
          (parsedUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = true
            useCaches = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", userAgent)
          }
      block(connection)
    } catch (_: SocketTimeoutException) {
      ValhallaHttpResponse.failure(ValhallaHttpFailureKind.TIMEOUT, "The tile request timed out.")
    } catch (_: InterruptedIOException) {
      val cancelled = Thread.currentThread().isInterrupted
      ValhallaHttpResponse.failure(
          if (cancelled) ValhallaHttpFailureKind.CANCELLED else ValhallaHttpFailureKind.NETWORK,
          if (cancelled) "The tile request was cancelled." else "The tile request was interrupted.")
    } catch (_: IOException) {
      ValhallaHttpResponse.failure(ValhallaHttpFailureKind.NETWORK, "The tile request failed.")
    } catch (_: RuntimeException) {
      ValhallaHttpResponse.failure(ValhallaHttpFailureKind.INTERNAL, "The HTTP transport failed.")
    } finally {
      connection?.disconnect()
    }
  }

  private fun validatedUrl(value: String): URL {
    val uri = URI(value)
    require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", true))
    require(!uri.host.isNullOrBlank())
    return uri.toURL()
  }

  private fun cancelledResponse(): ValhallaHttpResponse =
      ValhallaHttpResponse.failure(
          ValhallaHttpFailureKind.CANCELLED, "The tile request was cancelled.")

  private fun closeResponseBody(connection: HttpURLConnection) {
    try {
      connection.errorStream?.close()
      connection.inputStream?.close()
    } catch (_: IOException) {
      // The response is already complete from the caller's perspective.
    }
  }

  private fun matchesRequestedContentRange(
      header: String?,
      rangeOffset: Long,
      rangeSize: Long
  ): Boolean {
    val match = header?.let { CONTENT_RANGE_PATTERN.matchEntire(it.trim()) } ?: return false
    val start = match.groupValues[1].toLongOrNull() ?: return false
    val end = match.groupValues[2].toLongOrNull() ?: return false
    val total =
        match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
            ?: if (match.groupValues[3] == "*") null else return false
    return start == rangeOffset &&
        end == rangeOffset + rangeSize - 1 &&
        (total == null || total > end)
  }

  companion object {
    const val HEADER_LAST_MODIFIED: Int = 1
    const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 10_000
    const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 30_000
    const val DEFAULT_MAXIMUM_RESPONSE_BYTES: Int = 64 * 1024 * 1024
    const val DEFAULT_USER_AGENT: String = "valhalla-mobile-android"

    private const val MILLIS_PER_SECOND = 1_000L
    private val CONTENT_RANGE_PATTERN =
        Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
  }
}
