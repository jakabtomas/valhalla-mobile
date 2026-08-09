# Native JNI code reads these response fields and calls these interface methods by their JVM names.
-keep interface com.valhalla.valhalla.http.ValhallaHttpClient { *; }
-keep class com.valhalla.valhalla.http.ValhallaHttpResponse { *; }
-keepclassmembers class * implements com.valhalla.valhalla.http.ValhallaHttpClient {
    public com.valhalla.valhalla.http.ValhallaHttpResponse get(java.lang.String, long, long);
    public com.valhalla.valhalla.http.ValhallaHttpResponse head(java.lang.String, int);
}
