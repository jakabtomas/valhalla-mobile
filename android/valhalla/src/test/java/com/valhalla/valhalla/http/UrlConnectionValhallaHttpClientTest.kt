package com.valhalla.valhalla.http

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UrlConnectionValhallaHttpClientTest {
  private lateinit var server: TestHttpServer
  private lateinit var baseUrl: String

  @Before
  fun setUp() {
    server = TestHttpServer()
    baseUrl = server.baseUrl
  }

  @After
  fun tearDown() {
    server.close()
  }

  @Test
  fun getRangeRequiresAndReturnsExactRange() {
    val payload = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    server.handler = { request ->
      assertEquals("bytes=2-5", request.headers["range"])
      assertEquals("identity", request.headers["accept-encoding"])
      TestHttpResponse(
          status = 206,
          headers = mapOf("Content-Range" to "bytes 2-5/${payload.size}"),
          body = payload.copyOfRange(2, 6))
    }

    val response = UrlConnectionValhallaHttpClient().get("$baseUrl/archive", 2, 4)

    assertEquals(206, response.statusCode)
    assertEquals(null, response.failureKind)
    assertArrayEquals(byteArrayOf(2, 3, 4, 5), response.body)
  }

  @Test
  fun getRangeRejectsWrongContentRange() {
    server.handler = {
      TestHttpResponse(
          status = 206,
          headers = mapOf("Content-Range" to "bytes 0-3/8"),
          body = byteArrayOf(0, 1, 2, 3))
    }

    val response = UrlConnectionValhallaHttpClient().get("$baseUrl/archive", 2, 4)

    assertEquals(ValhallaHttpFailureKind.INVALID_RESPONSE, response.failureKind)
  }

  @Test
  fun getRangeRejectsImpossibleContentRangeTotal() {
    server.handler = {
      TestHttpResponse(
          status = 206,
          headers = mapOf("Content-Range" to "bytes 2-5/5"),
          body = byteArrayOf(2, 3, 4, 5))
    }

    val response = UrlConnectionValhallaHttpClient().get("$baseUrl/archive", 2, 4)

    assertEquals(ValhallaHttpFailureKind.INVALID_RESPONSE, response.failureKind)
  }

  @Test
  fun getMapsHttpFailureWithoutReadingAnUnboundedErrorBody() {
    server.handler = { TestHttpResponse(status = 503, body = ByteArray(1024)) }

    val response = UrlConnectionValhallaHttpClient().get("$baseUrl/failure")

    assertEquals(503, response.statusCode)
    assertEquals(ValhallaHttpFailureKind.HTTP_STATUS, response.failureKind)
    assertTrue(response.body.isEmpty())
  }

  @Test
  fun headReturnsLastModifiedOnlyWhenRequested() {
    server.handler = {
      TestHttpResponse(
          status = 200, headers = mapOf("Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT"))
    }

    val response =
        UrlConnectionValhallaHttpClient()
            .head("$baseUrl/metadata", UrlConnectionValhallaHttpClient.HEADER_LAST_MODIFIED)

    assertEquals(1_445_412_480L, response.lastModifiedEpochSeconds)
  }

  @Test
  fun clientSupportsConcurrentIndependentRangeRequests() {
    val requestedRanges = Collections.synchronizedSet(mutableSetOf<String>())
    val requestsStarted = CountDownLatch(4)
    val releaseResponses = CountDownLatch(1)
    server.handler = { request ->
      val range = checkNotNull(request.headers["range"])
      requestedRanges += range
      requestsStarted.countDown()
      releaseResponses.await(5, TimeUnit.SECONDS)
      val bounds = range.removePrefix("bytes=").split("-").map(String::toInt)
      val body = ByteArray(bounds[1] - bounds[0] + 1) { bounds[0].toByte() }
      TestHttpResponse(
          status = 206,
          headers = mapOf("Content-Range" to "bytes ${bounds[0]}-${bounds[1]}/100"),
          body = body)
    }
    val client = UrlConnectionValhallaHttpClient()
    val executor = Executors.newFixedThreadPool(4)

    val futures =
        (0 until 4).map { index ->
          executor.submit<ValhallaHttpResponse> { client.get("$baseUrl/concurrent", index * 4L, 4) }
        }
    assertTrue(requestsStarted.await(5, TimeUnit.SECONDS))
    releaseResponses.countDown()

    futures.forEachIndexed { index, future ->
      val response = future.get(5, TimeUnit.SECONDS)
      assertEquals(null, response.failureKind)
      assertArrayEquals(ByteArray(4) { (index * 4).toByte() }, response.body)
    }
    executor.shutdownNow()
  }

  @Test
  fun offlineOnlyClientNeverAttemptsNetworkAndReturnsMissingCoverage() {
    val response =
        OfflineOnlyValhallaHttpClient.get(
            "https://this-host-must-never-be-resolved.invalid/tiles/{tilePath}", 0, 512)

    assertEquals(ValhallaHttpFailureKind.MISSING_COVERAGE, response.failureKind)
    assertEquals(0, response.statusCode)
  }

  @Test
  fun interruptedRoutingThreadIsCancelledBeforeNetworkAccess() {
    val executor = Executors.newSingleThreadExecutor()
    try {
      val response =
          executor
              .submit<ValhallaHttpResponse> {
                Thread.currentThread().interrupt()
                UrlConnectionValhallaHttpClient()
                    .get("https://this-host-must-never-be-resolved.invalid/archive", 0, 512)
              }
              .get(5, TimeUnit.SECONDS)

      assertEquals(ValhallaHttpFailureKind.CANCELLED, response.failureKind)
      assertEquals(0, response.statusCode)
    } finally {
      executor.shutdownNow()
    }
  }

  private data class TestHttpRequest(
      val method: String,
      val path: String,
      val headers: Map<String, String>
  )

  private data class TestHttpResponse(
      val status: Int,
      val headers: Map<String, String> = emptyMap(),
      val body: ByteArray = byteArrayOf()
  )

  private class TestHttpServer : Closeable {
    private val socket =
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
    private val clients: ExecutorService = Executors.newCachedThreadPool()
    private val acceptThread =
        Thread(
                {
                  while (!socket.isClosed) {
                    try {
                      val client = socket.accept()
                      clients.execute { handle(client) }
                    } catch (error: Exception) {
                      if (!socket.isClosed) throw error
                    }
                  }
                },
                "valhalla-http-test-server")
            .apply {
              isDaemon = true
              start()
            }

    val baseUrl: String = "http://127.0.0.1:${socket.localPort}"
    @Volatile var handler: (TestHttpRequest) -> TestHttpResponse = { TestHttpResponse(404) }

    private fun handle(client: Socket) {
      client.use {
        val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.US_ASCII))
        val requestLine = reader.readLine()?.split(" ") ?: return
        if (requestLine.size < 2) return
        val headers = mutableMapOf<String, String>()
        while (true) {
          val line = reader.readLine() ?: return
          if (line.isEmpty()) break
          val separator = line.indexOf(':')
          if (separator > 0) {
            headers[line.substring(0, separator).lowercase(Locale.US)] =
                line.substring(separator + 1).trim()
          }
        }
        val request = TestHttpRequest(requestLine[0], requestLine[1], headers)
        val response = handler(request)
        val reason = if (response.status in 200..299) "OK" else "Failure"
        val output = it.getOutputStream()
        val headerText = buildString {
          append("HTTP/1.1 ${response.status} $reason\r\n")
          response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
          append("Content-Length: ${response.body.size}\r\n")
          append("Connection: close\r\n\r\n")
        }
        output.write(headerText.toByteArray(Charsets.US_ASCII))
        if (request.method != "HEAD") {
          output.write(response.body)
        }
        output.flush()
      }
    }

    override fun close() {
      socket.close()
      clients.shutdownNow()
      acceptThread.join(2_000)
    }
  }
}
