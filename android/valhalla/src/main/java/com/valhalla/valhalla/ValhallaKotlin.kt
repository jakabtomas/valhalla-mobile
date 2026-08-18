package com.valhalla.valhalla

import com.valhalla.valhalla.http.ValhallaHttpClient

internal class ValhallaKotlin {
  companion object {
    init {
      System.loadLibrary("valhalla-wrapper")
    }
  }

  external fun route(request: String, configPath: String, httpClient: ValhallaHttpClient): String

  external fun traceAttributes(
      request: String,
      configPath: String,
      httpClient: ValhallaHttpClient
  ): String
}
