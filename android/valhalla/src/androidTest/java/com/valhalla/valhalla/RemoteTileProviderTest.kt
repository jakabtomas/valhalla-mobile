package com.valhalla.valhalla

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.valhalla.http.OfflineOnlyValhallaHttpClient
import com.valhalla.valhalla.http.ValhallaHttpClient
import com.valhalla.valhalla.http.ValhallaHttpFailureKind
import com.valhalla.valhalla.http.ValhallaHttpResponse
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteTileProviderTest {
  private lateinit var context: Context
  private lateinit var testDirectory: File

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    testDirectory =
        File(context.cacheDir, "remote-provider-${UUID.randomUUID()}").apply { mkdirs() }
  }

  @After
  fun tearDown() {
    testDirectory.deleteRecursively()
  }

  @Test
  fun injectedProviderLoadsRemoteLooseTilesWithoutNetwork() {
    val provider = LooseAssetProvider(context)
    val actor =
        ValhallaActor(createRemoteConfig("provider://loose/{tilePath}", "loose-cache"), provider)

    val response = JSONObject(actor.route(successfulRouteRequest()))

    assertTrue("Remote loose-tile route failed: $response", response.has("trip"))
    assertTrue(provider.requestedPaths.any { it.endsWith("2/000/763/926.gph") })
  }

  @Test
  fun injectedProviderServesIndexedTarRanges() {
    val provider = IndexedTarAssetProvider(context)
    val actor =
        ValhallaActor(
            createRemoteConfig("provider://indexed/valhalla_tiles.tar", "indexed-cache"), provider)

    val response = JSONObject(actor.route(successfulRouteRequest()))

    assertTrue("Remote indexed-tar route failed: $response", response.has("trip"))
    assertTrue(provider.requestedRanges.contains(0L to 512L))
    assertTrue(provider.requestedRanges.any { (offset, size) -> offset > 512L && size > 0L })
  }

  @Test
  fun gzippedIndexedTarValidatesDecompressedHeadersAcrossMultipleRanges() {
    val cacheName = "gzip-indexed-cache"
    val tileUrl = "provider://indexed/valhalla_tiles_gzip.tar"
    val provider = IndexedTarAssetProvider(context, "valhalla_tiles_indexed_gzip.tar")
    writeIdFile(cacheName, tileUrl, fixtureTileChecksum())
    val actor = ValhallaActor(createRemoteConfig(tileUrl, cacheName, tileUrlGzip = true), provider)

    val response = JSONObject(actor.route(successfulRouteRequest()))

    assertTrue("Gzipped indexed-tar route failed: $response", response.has("trip"))
    assertTrue(provider.tilePayloadRanges.size >= 2)
    assertTrue(provider.rawCompressedHeaderWords.toSet().size >= 2)
  }

  @Test
  fun offlineOnlyProviderReturnsTypedMissingCoverageWithoutNetwork() {
    val actor =
        ValhallaActor(
            createRemoteConfig("https://must-not-resolve.invalid/{tilePath}", "offline-cache"),
            OfflineOnlyValhallaHttpClient)

    val response = JSONObject(actor.route(successfulRouteRequest()))

    assertEquals("tile_fetch", response.getString("error_type"))
    assertEquals("missing_coverage", response.getString("failure_kind"))
    assertEquals(-2, response.getInt("code"))
  }

  @Test
  fun providerExceptionReturnsTypedCallbackFailure() {
    val provider =
        object : ValhallaHttpClient {
          override fun get(url: String, rangeOffset: Long, rangeSize: Long): ValhallaHttpResponse =
              error("Provider callback failure")

          override fun head(url: String, headerMask: Int): ValhallaHttpResponse =
              error("Provider callback failure")
        }
    val actor =
        ValhallaActor(
            createRemoteConfig("provider://throws/{tilePath}", "callback-cache"), provider)

    val response = JSONObject(actor.route(successfulRouteRequest()))

    assertEquals("tile_fetch", response.getString("error_type"))
    assertEquals("callback_exception", response.getString("failure_kind"))
  }

  private fun createRemoteConfig(
      tileUrl: String,
      cacheName: String,
      tileUrlGzip: Boolean = false
  ): String {
    val root =
        context.assets.open("config.json").bufferedReader().use { reader ->
          JSONObject(reader.readText())
        }
    root.getJSONObject("mjolnir").apply {
      remove("tile_extract")
      put("tile_dir", File(testDirectory, cacheName).absolutePath)
      put("tile_url", tileUrl)
      put("tile_url_gz", tileUrlGzip)
    }
    root.getJSONObject("loki").put("use_connectivity", false)
    return File(testDirectory, "$cacheName.json").apply { writeText(root.toString()) }.absolutePath
  }

  private fun writeIdFile(cacheName: String, tileUrl: String, checksum: Long) {
    File(testDirectory, cacheName).apply {
      mkdirs()
      File(this, "id.txt").writeText("$tileUrl\n$checksum\n")
    }
  }

  private fun fixtureTileChecksum(): Long =
      context.assets.open("valhalla_tiles/2/000/763/926.gph").use { input ->
        val header = ByteArray(GRAPH_TILE_HEADER_BYTES)
        check(input.read(header) == header.size)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getLong(GRAPH_TILE_CHECKSUM_OFFSET)
      }

  private fun successfulRouteRequest(): String =
      """
      {
        "locations": [
          {"lat":42.5063,"lon":1.5218},
          {"lat":42.5086,"lon":1.5394}
        ],
        "costing":"auto",
        "units":"miles"
      }
      """
          .trimIndent()

  private class LooseAssetProvider(private val context: Context) : ValhallaHttpClient {
    val requestedPaths = mutableListOf<String>()

    override fun get(url: String, rangeOffset: Long, rangeSize: Long): ValhallaHttpResponse {
      check(rangeSize == 0L)
      val path = url.substringAfter("provider://loose/")
      requestedPaths += path
      return try {
        ValhallaHttpResponse.success(
            200, context.assets.open("valhalla_tiles/$path").use { it.readBytes() })
      } catch (_: Exception) {
        ValhallaHttpResponse.failure(
            ValhallaHttpFailureKind.HTTP_STATUS, "Fixture tile not found.", 404)
      }
    }

    override fun head(url: String, headerMask: Int): ValhallaHttpResponse =
        ValhallaHttpResponse.success(200)
  }

  private class IndexedTarAssetProvider(
      context: Context,
      assetName: String = "valhalla_tiles_indexed.tar"
  ) : ValhallaHttpClient {
    private val archive = context.assets.open(assetName).use { input -> input.readBytes() }
    private var rangeRequestCount = 0
    val requestedRanges = mutableListOf<Pair<Long, Long>>()
    val tilePayloadRanges = mutableListOf<Pair<Long, Long>>()
    val rawCompressedHeaderWords = mutableListOf<Long>()

    override fun get(url: String, rangeOffset: Long, rangeSize: Long): ValhallaHttpResponse {
      requestedRanges += rangeOffset to rangeSize
      if (rangeOffset < 0 ||
          rangeSize <= 0 ||
          rangeOffset > archive.size.toLong() - rangeSize ||
          rangeSize > Int.MAX_VALUE) {
        return ValhallaHttpResponse.failure(
            ValhallaHttpFailureKind.INVALID_REQUEST, "Invalid fixture range.")
      }
      val start = rangeOffset.toInt()
      val end = start + rangeSize.toInt()
      val responseBytes = archive.copyOfRange(start, end)
      val requestIndex = rangeRequestCount++
      if (requestIndex >= INDEXED_TAR_METADATA_REQUESTS &&
          responseBytes.size >= GRAPH_TILE_HEADER_BYTES) {
        tilePayloadRanges += rangeOffset to rangeSize
        rawCompressedHeaderWords +=
            ByteBuffer.wrap(responseBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getLong(GRAPH_TILE_CHECKSUM_OFFSET)
      }
      return ValhallaHttpResponse.success(206, responseBytes)
    }

    override fun head(url: String, headerMask: Int): ValhallaHttpResponse =
        ValhallaHttpResponse.success(200)
  }

  private companion object {
    const val INDEXED_TAR_METADATA_REQUESTS = 2
    const val GRAPH_TILE_HEADER_BYTES = 272
    const val GRAPH_TILE_CHECKSUM_OFFSET = 88
  }
}
