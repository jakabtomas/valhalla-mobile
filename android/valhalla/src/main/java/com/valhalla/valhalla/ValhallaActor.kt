package com.valhalla.valhalla

import com.valhalla.valhalla.http.UrlConnectionValhallaHttpClient
import com.valhalla.valhalla.http.ValhallaHttpClient

interface ValhallaActorProviding {
  fun route(request: String): String
}

/**
 * Access with raw unchecked strings to the Valhalla routing engine. This class is available, but
 * not recommended for general use.
 *
 * @property configPath
 */
class ValhallaActor
@JvmOverloads
constructor(
    private val configPath: String,
    private val httpClient: ValhallaHttpClient = UrlConnectionValhallaHttpClient()
) : ValhallaActorProviding {
  private val valhallaKotlin = ValhallaKotlin()

  /**
   * Run a route request to the Valhalla routing engine. This assumes your config path is valid,
   * tiles exist and your request string is valid.
   *
   * @param request
   * @return
   */
  override fun route(request: String): String {
    return valhallaKotlin.route(request, configPath, httpClient)
  }
}
