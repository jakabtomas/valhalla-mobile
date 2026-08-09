#pragma once

#ifdef __ANDROID__

#include "valhalla_actor.h"

#include <jni.h>

#include <memory>
#include <mutex>
#include <optional>
#include <string>

struct TileFetchError {
  std::string operation;
  std::string failure_kind;
  std::string message;
  long http_code = 0;
};

class TileFetchErrorState {
public:
  void record(TileFetchError error);
  std::optional<TileFetchError> last_error() const;

private:
  mutable std::mutex mutex_;
  std::optional<TileFetchError> last_error_;
};

/**
 * Adapts an application-provided Kotlin ValhallaHttpClient to Valhalla's synchronous tile getter.
 *
 * The client and response class are retained as JNI global references. Calls therefore remain safe
 * if Valhalla invokes the getter from an attached native thread in a future engine release.
 */
class AndroidHttpClient final : public ValhallaMobileHttpClient {
public:
  AndroidHttpClient(JNIEnv* env,
                    jobject http_client,
                    std::shared_ptr<TileFetchErrorState> error_state);
  ~AndroidHttpClient() override;

  valhalla::baldr::tile_getter_t::GET_response_t
  get(const std::string& url, uint64_t range_offset, uint64_t range_size) override;

  valhalla::baldr::tile_getter_t::HEAD_response_t
  head(const std::string& url,
       valhalla::baldr::tile_getter_t::header_mask_t header_mask) override;

  AndroidHttpClient(const AndroidHttpClient&) = delete;
  AndroidHttpClient& operator=(const AndroidHttpClient&) = delete;

private:
  JavaVM* java_vm_ = nullptr;
  jobject http_client_ = nullptr;
  jclass response_class_ = nullptr;
  jmethodID get_method_ = nullptr;
  jmethodID head_method_ = nullptr;
  jfieldID status_code_field_ = nullptr;
  jfieldID body_field_ = nullptr;
  jfieldID last_modified_field_ = nullptr;
  jfieldID failure_code_field_ = nullptr;
  jfieldID failure_message_field_ = nullptr;
  std::shared_ptr<TileFetchErrorState> error_state_;

  void record_failure(const std::string& operation,
                      int failure_code,
                      long http_code,
                      const std::string& message);
};

std::string make_error_json(int code, const std::string& message);
std::string make_tile_fetch_error_json(const TileFetchError& error);

#endif
