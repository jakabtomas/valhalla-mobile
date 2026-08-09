#include "android_http_client.h"

#ifdef __ANDROID__

#include <rapidjson/stringbuffer.h>
#include <rapidjson/writer.h>

#include <algorithm>
#include <limits>
#include <stdexcept>
#include <utility>

namespace {

constexpr size_t kMaximumJniResponseBytes = 256U * 1024U * 1024U;
constexpr size_t kMaximumFailureMessageBytes = 512U;

class ScopedJniEnv {
public:
  explicit ScopedJniEnv(JavaVM* java_vm) : java_vm_(java_vm) {
    if (!java_vm_) {
      return;
    }

    const auto status = java_vm_->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
      if (java_vm_->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
        detach_ = true;
      } else {
        env_ = nullptr;
      }
    } else if (status != JNI_OK) {
      env_ = nullptr;
    }
  }

  ~ScopedJniEnv() {
    if (detach_) {
      java_vm_->DetachCurrentThread();
    }
  }

  JNIEnv* get() const {
    return env_;
  }

private:
  JavaVM* java_vm_;
  JNIEnv* env_ = nullptr;
  bool detach_ = false;
};

std::string failure_kind(const int code) {
  switch (code) {
    case 1:
      return "invalid_request";
    case 2:
      return "network";
    case 3:
      return "timeout";
    case 4:
      return "http_status";
    case 5:
      return "invalid_response";
    case 6:
      return "cancelled";
    case 7:
      return "callback_exception";
    case 8:
      return "internal";
    case 9:
      return "missing_coverage";
    default:
      return "unknown";
  }
}

long checked_http_code(const jlong value) {
  if (value <= 0) {
    return 0;
  }
  return static_cast<long>(
      std::min<jlong>(value, static_cast<jlong>(std::numeric_limits<long>::max())));
}

std::string copy_java_string(JNIEnv* env, jstring value) {
  if (!value) {
    return {};
  }

  const char* utf_chars = env->GetStringUTFChars(value, nullptr);
  if (!utf_chars) {
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
    }
    return {};
  }

  std::string result{utf_chars};
  env->ReleaseStringUTFChars(value, utf_chars);
  if (result.size() > kMaximumFailureMessageBytes) {
    result.resize(kMaximumFailureMessageBytes);
  }
  return result;
}

template <class Response> void mark_failure(Response& response, const long http_code) {
  response.status_ = valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
  response.http_code_ = http_code;
}

std::string write_error_json(const int code,
                             const std::string& message,
                             const TileFetchError* tile_error) {
  rapidjson::StringBuffer buffer;
  rapidjson::Writer<rapidjson::StringBuffer> writer(buffer);

  writer.StartObject();
  writer.Key("code");
  writer.Int(code);
  writer.Key("message");
  writer.String(message.c_str(), static_cast<rapidjson::SizeType>(message.size()));
  if (tile_error) {
    writer.Key("error_type");
    writer.String("tile_fetch");
    writer.Key("operation");
    writer.String(tile_error->operation.c_str(),
                  static_cast<rapidjson::SizeType>(tile_error->operation.size()));
    writer.Key("failure_kind");
    writer.String(tile_error->failure_kind.c_str(),
                  static_cast<rapidjson::SizeType>(tile_error->failure_kind.size()));
    writer.Key("http_code");
    writer.Int64(tile_error->http_code);
    if (!tile_error->message.empty()) {
      writer.Key("detail");
      writer.String(tile_error->message.c_str(),
                    static_cast<rapidjson::SizeType>(tile_error->message.size()));
    }
  }
  writer.EndObject();

  return {buffer.GetString(), buffer.GetSize()};
}

} // namespace

void TileFetchErrorState::record(TileFetchError error) {
  std::lock_guard lock{mutex_};
  last_error_ = std::move(error);
}

std::optional<TileFetchError> TileFetchErrorState::last_error() const {
  std::lock_guard lock{mutex_};
  return last_error_;
}

AndroidHttpClient::AndroidHttpClient(JNIEnv* env,
                                     jobject http_client,
                                     std::shared_ptr<TileFetchErrorState> error_state)
    : error_state_(std::move(error_state)) {
  if (!env || !http_client || !error_state_) {
    throw std::invalid_argument("Android HTTP client arguments must not be null");
  }
  if (env->GetJavaVM(&java_vm_) != JNI_OK || !java_vm_) {
    throw std::runtime_error("Could not obtain JavaVM for the Android HTTP client");
  }

  try {
    http_client_ = env->NewGlobalRef(http_client);
    if (!http_client_) {
      throw std::runtime_error("Could not retain the Android HTTP client");
    }

    jclass local_response_class =
        env->FindClass("com/valhalla/valhalla/http/ValhallaHttpResponse");
    if (!local_response_class || env->ExceptionCheck()) {
      env->ExceptionClear();
      throw std::runtime_error("Could not find ValhallaHttpResponse");
    }
    response_class_ = static_cast<jclass>(env->NewGlobalRef(local_response_class));
    env->DeleteLocalRef(local_response_class);
    if (!response_class_) {
      throw std::runtime_error("Could not retain ValhallaHttpResponse");
    }

    jclass client_class = env->GetObjectClass(http_client);
    if (!client_class || env->ExceptionCheck()) {
      env->ExceptionClear();
      throw std::runtime_error("Could not inspect the Android HTTP client");
    }
    get_method_ =
        env->GetMethodID(client_class, "get",
                         "(Ljava/lang/String;JJ)"
                         "Lcom/valhalla/valhalla/http/ValhallaHttpResponse;");
    head_method_ =
        env->GetMethodID(client_class, "head",
                         "(Ljava/lang/String;I)"
                         "Lcom/valhalla/valhalla/http/ValhallaHttpResponse;");
    env->DeleteLocalRef(client_class);

    status_code_field_ = env->GetFieldID(response_class_, "statusCode", "J");
    body_field_ = env->GetFieldID(response_class_, "body", "[B");
    last_modified_field_ =
        env->GetFieldID(response_class_, "lastModifiedEpochSeconds", "J");
    failure_code_field_ = env->GetFieldID(response_class_, "failureCode", "I");
    failure_message_field_ =
        env->GetFieldID(response_class_, "failureMessage", "Ljava/lang/String;");

    if (env->ExceptionCheck() || !get_method_ || !head_method_ || !status_code_field_ ||
        !body_field_ || !last_modified_field_ || !failure_code_field_ ||
        !failure_message_field_) {
      env->ExceptionClear();
      throw std::runtime_error("Android HTTP client JNI contract is incomplete");
    }
  } catch (...) {
    if (response_class_) {
      env->DeleteGlobalRef(response_class_);
      response_class_ = nullptr;
    }
    if (http_client_) {
      env->DeleteGlobalRef(http_client_);
      http_client_ = nullptr;
    }
    throw;
  }
}

AndroidHttpClient::~AndroidHttpClient() {
  ScopedJniEnv scoped_env{java_vm_};
  if (auto* env = scoped_env.get()) {
    if (response_class_) {
      env->DeleteGlobalRef(response_class_);
    }
    if (http_client_) {
      env->DeleteGlobalRef(http_client_);
    }
  }
}

valhalla::baldr::tile_getter_t::GET_response_t
AndroidHttpClient::get(const std::string& url,
                       const uint64_t range_offset,
                       const uint64_t range_size) {
  valhalla::baldr::tile_getter_t::GET_response_t response;
  ScopedJniEnv scoped_env{java_vm_};
  auto* env = scoped_env.get();
  if (!env) {
    record_failure("GET", 7, 0, "Could not attach the native routing thread to the JVM.");
    return response;
  }

  if (range_offset > static_cast<uint64_t>(std::numeric_limits<jlong>::max()) ||
      range_size > static_cast<uint64_t>(std::numeric_limits<jlong>::max())) {
    record_failure("GET", 1, 0, "The requested byte range is not representable on Android.");
    return response;
  }

  jstring request_url = env->NewStringUTF(url.c_str());
  if (!request_url || env->ExceptionCheck()) {
    env->ExceptionClear();
    record_failure("GET", 1, 0, "The tile URL could not be converted for Android.");
    return response;
  }

  jobject result =
      env->CallObjectMethod(http_client_, get_method_, request_url,
                            static_cast<jlong>(range_offset), static_cast<jlong>(range_size));
  env->DeleteLocalRef(request_url);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    record_failure("GET", 7, 0, "The application HTTP provider threw an exception.");
    return response;
  }
  if (!result) {
    record_failure("GET", 7, 0, "The application HTTP provider returned null.");
    return response;
  }

  const auto status_code = checked_http_code(env->GetLongField(result, status_code_field_));
  const auto failure_code = env->GetIntField(result, failure_code_field_);
  auto body = static_cast<jbyteArray>(env->GetObjectField(result, body_field_));
  auto failure_message =
      static_cast<jstring>(env->GetObjectField(result, failure_message_field_));

  if (env->ExceptionCheck() || !body) {
    env->ExceptionClear();
    if (failure_message) {
      env->DeleteLocalRef(failure_message);
    }
    if (body) {
      env->DeleteLocalRef(body);
    }
    env->DeleteLocalRef(result);
    record_failure("GET", 7, status_code, "The application HTTP response is invalid.");
    mark_failure(response, status_code);
    return response;
  }

  if (failure_code != 0) {
    const auto message = copy_java_string(env, failure_message);
    if (failure_message) {
      env->DeleteLocalRef(failure_message);
    }
    env->DeleteLocalRef(body);
    env->DeleteLocalRef(result);
    record_failure("GET", failure_code, status_code, message);
    mark_failure(response, status_code);
    return response;
  }

  const auto body_size = env->GetArrayLength(body);
  const bool invalid_status =
      range_size > 0 ? status_code != 206 : status_code < 200 || status_code >= 300;
  const bool invalid_size =
      body_size < 0 || static_cast<size_t>(body_size) > kMaximumJniResponseBytes ||
      (range_size > 0 && static_cast<uint64_t>(body_size) != range_size);
  if (invalid_status || invalid_size) {
    if (failure_message) {
      env->DeleteLocalRef(failure_message);
    }
    env->DeleteLocalRef(body);
    env->DeleteLocalRef(result);
    record_failure("GET", 5, status_code,
                   invalid_status ? "The application HTTP provider returned an invalid status."
                                  : "The application HTTP provider returned an invalid body size.");
    mark_failure(response, status_code);
    return response;
  }

  response.bytes_.resize(static_cast<size_t>(body_size));
  if (body_size > 0) {
    env->GetByteArrayRegion(body, 0, body_size,
                            reinterpret_cast<jbyte*>(response.bytes_.data()));
  }
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    response.bytes_.clear();
    record_failure("GET", 7, status_code, "Could not copy the application HTTP response.");
    mark_failure(response, status_code);
  } else {
    response.status_ = valhalla::baldr::tile_getter_t::status_code_t::SUCCESS;
    response.http_code_ = status_code;
  }

  if (failure_message) {
    env->DeleteLocalRef(failure_message);
  }
  env->DeleteLocalRef(body);
  env->DeleteLocalRef(result);
  return response;
}

valhalla::baldr::tile_getter_t::HEAD_response_t AndroidHttpClient::head(
    const std::string& url,
    const valhalla::baldr::tile_getter_t::header_mask_t header_mask) {
  valhalla::baldr::tile_getter_t::HEAD_response_t response;
  ScopedJniEnv scoped_env{java_vm_};
  auto* env = scoped_env.get();
  if (!env) {
    record_failure("HEAD", 7, 0, "Could not attach the native routing thread to the JVM.");
    return response;
  }

  jstring request_url = env->NewStringUTF(url.c_str());
  if (!request_url || env->ExceptionCheck()) {
    env->ExceptionClear();
    record_failure("HEAD", 1, 0, "The tile URL could not be converted for Android.");
    return response;
  }

  jobject result = env->CallObjectMethod(http_client_, head_method_, request_url,
                                         static_cast<jint>(header_mask));
  env->DeleteLocalRef(request_url);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    record_failure("HEAD", 7, 0, "The application HTTP provider threw an exception.");
    return response;
  }
  if (!result) {
    record_failure("HEAD", 7, 0, "The application HTTP provider returned null.");
    return response;
  }

  const auto status_code = checked_http_code(env->GetLongField(result, status_code_field_));
  const auto last_modified = env->GetLongField(result, last_modified_field_);
  const auto failure_code = env->GetIntField(result, failure_code_field_);
  auto failure_message =
      static_cast<jstring>(env->GetObjectField(result, failure_message_field_));

  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    if (failure_message) {
      env->DeleteLocalRef(failure_message);
    }
    env->DeleteLocalRef(result);
    record_failure("HEAD", 7, status_code, "The application HTTP response is invalid.");
    mark_failure(response, status_code);
    return response;
  }

  if (failure_code != 0 || status_code < 200 || status_code >= 300 || last_modified < 0) {
    const auto message = copy_java_string(env, failure_message);
    if (failure_message) {
      env->DeleteLocalRef(failure_message);
    }
    env->DeleteLocalRef(result);
    record_failure("HEAD", failure_code == 0 ? 5 : failure_code, status_code,
                   message.empty() ? "The application HTTP provider returned an invalid response."
                                   : message);
    mark_failure(response, status_code);
    return response;
  }

  response.status_ = valhalla::baldr::tile_getter_t::status_code_t::SUCCESS;
  response.http_code_ = status_code;
  response.last_modified_time_ = static_cast<uint64_t>(last_modified);
  if (failure_message) {
    env->DeleteLocalRef(failure_message);
  }
  env->DeleteLocalRef(result);
  return response;
}

void AndroidHttpClient::record_failure(const std::string& operation,
                                       const int failure_code,
                                       const long http_code,
                                       const std::string& message) {
  error_state_->record({operation, failure_kind(failure_code), message, http_code});
}

std::string make_error_json(const int code, const std::string& message) {
  return write_error_json(code, message, nullptr);
}

std::string make_tile_fetch_error_json(const TileFetchError& error) {
  const std::string message = error.failure_kind == "missing_coverage"
                                  ? "Routing data is not installed for this area."
                                  : "A routing tile could not be loaded.";
  return write_error_json(-2, message, &error);
}

#endif
