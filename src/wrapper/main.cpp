
#include <valhalla/worker.h>
#include "main.h"
#include "valhalla_actor.h"

#ifdef __ANDROID__
// The Android JNI interface uses a different function signature.
#include "android_http_client.h"
#include <jni.h>

using actor_action_t = std::string (ValhallaActor::*)(const std::string&);

static jstring execute_android_action(JNIEnv *env,
                                      jstring jRequest,
                                      jstring jConfigPath,
                                      jobject jHttpClient,
                                      actor_action_t action,
                                      const char* action_name) {
    if (!jRequest || !jConfigPath || !jHttpClient) {
        const auto error = make_error_json(-1, "Native routing arguments must not be null");
        return env->NewStringUTF(error.c_str());
    }

    const char *request = env->GetStringUTFChars(jRequest, nullptr);
    const char *config_path = env->GetStringUTFChars(jConfigPath, nullptr);
    if (!request || !config_path) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        if (request) {
            env->ReleaseStringUTFChars(jRequest, request);
        }
        if (config_path) {
            env->ReleaseStringUTFChars(jConfigPath, config_path);
        }
        const auto error = make_error_json(-1, "Native routing strings could not be read");
        return env->NewStringUTF(error.c_str());
    }

    std::string result;
    auto tile_error_state = std::make_shared<TileFetchErrorState>();
    try {
        // TODO: Android currently creates a new actor every time. Optimize to be like iOS later.
        auto http_client =
            std::make_unique<AndroidHttpClient>(env, jHttpClient, tile_error_state);
        ValhallaActor valhallaActor(config_path, std::move(http_client));
        result = (valhallaActor.*action)(request);
    } catch (const valhalla::valhalla_exception_t &err) {
        printf("[ValhallaActor] %s valhalla_exception: %s\n", action_name, err.what());
        const auto tile_error = tile_error_state->last_error();
        result = tile_error ? make_tile_fetch_error_json(*tile_error)
                            : make_error_json(err.code, err.message);
    } catch (const std::exception &err) {
        printf("[ValhallaActor] %s std::exception: %s\n", action_name, err.what());
        const auto tile_error = tile_error_state->last_error();
        result = tile_error ? make_tile_fetch_error_json(*tile_error)
                            : make_error_json(-1, err.what());
    } catch (...) {
        printf("[ValhallaActor] %s unknown exception", action_name);
        const auto tile_error = tile_error_state->last_error();
        result = tile_error ? make_tile_fetch_error_json(*tile_error)
                            : make_error_json(-1, "unknown exception");
    }

    env->ReleaseStringUTFChars(jRequest, request);
    env->ReleaseStringUTFChars(jConfigPath, config_path);

    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_valhalla_valhalla_ValhallaKotlin_route(JNIEnv *env,
                                                jobject thiz,
                                                jstring jRequest,
                                                jstring jConfigPath,
                                                jobject jHttpClient) {
    return execute_android_action(
        env, jRequest, jConfigPath, jHttpClient, &ValhallaActor::route, "route");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_valhalla_valhalla_ValhallaKotlin_traceAttributes(JNIEnv *env,
                                                          jobject thiz,
                                                          jstring jRequest,
                                                          jstring jConfigPath,
                                                          jobject jHttpClient) {
    return execute_android_action(
        env,
        jRequest,
        jConfigPath,
        jHttpClient,
        &ValhallaActor::trace_attributes,
        "trace_attributes");
}

#elif __APPLE__
void* create_valhalla_actor(const char *config_path, ValhallaMobileHttpClient* http_client) {
    return new ValhallaActor(
        config_path, std::unique_ptr<ValhallaMobileHttpClient>(http_client));
}

void delete_valhalla_actor(void* actor) {
    delete ((ValhallaActor*) actor);
}

std::string route(const char *request, void* actor) {
    std::string result;
    try {
        result = ((ValhallaActor*) actor)->route(request);
    } catch (const valhalla::valhalla_exception_t &err) {
        printf("[ValhallaActor] route valhalla_exception: %s\n", err.what());
        std::string code = std::to_string(err.code);
        std::string message = err.message.c_str();

        result = "{\"code\":" + code + ",\"message\":\"" + message + "\"}";
    } catch (const std::exception &err) {
        printf("[ValhallaActor] route std::exception: %s\n", err.what());
        result = "{\"code\":-1,\"message\":\"" + std::string(err.what()) + "\"}";
    } catch (...) {
        printf("[ValhallaActor] route unknown exception");
        result = "{\"code\":-1,\"message\":\"unknown exception\"}";
    }

    return result;
}

std::string trace_attributes(const char *request, void* actor) {
    std::string result;
    try {
        result = ((ValhallaActor*) actor)->trace_attributes(request);
    } catch (const valhalla::valhalla_exception_t &err) {
        printf("[ValhallaActor] trace_attributes valhalla_exception: %s\n", err.what());
        result = "{\"code\":" + std::to_string(err.code) +
            ",\"message\":\"" + err.message + "\"}";
    } catch (const std::exception &err) {
        printf("[ValhallaActor] trace_attributes std::exception: %s\n", err.what());
        result = "{\"code\":-1,\"message\":\"" + std::string(err.what()) + "\"}";
    } catch (...) {
        printf("[ValhallaActor] trace_attributes unknown exception");
        result = "{\"code\":-1,\"message\":\"unknown exception\"}";
    }
    return result;
}
#endif
