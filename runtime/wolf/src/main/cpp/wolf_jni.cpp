// Thin JNI surface matching runtime/api WolfNativeContract.kt.
// All logic lives in the JNI-free core (wolf/wolf_core.h); this file only
// marshals values across the boundary.

#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "wolf/wolf_core.h"

namespace {

std::string toStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return std::string();
    }
    const char* utf = env->GetStringUTFChars(value, nullptr);
    const std::string result(utf == nullptr ? std::string() : std::string(utf));
    if (utf != nullptr) {
        env->ReleaseStringUTFChars(value, utf);
    }
    return result;
}

jbyteArray toByteArray(JNIEnv* env, const std::vector<uint8_t>& payload) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(payload.size()));
    if (array != nullptr && !payload.empty()) {
        env->SetByteArrayRegion(
            array, 0, static_cast<jsize>(payload.size()),
            reinterpret_cast<const jbyte*>(payload.data()));
    }
    return array;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_sdlVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF(wolf::sdlRuntimeVersion());
}

JNIEXPORT jboolean JNICALL
Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeSmokeTest(JNIEnv*, jobject) {
    return wolf::smokeTest() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLoadGame(
        JNIEnv* env, jobject, jstring gameId, jstring gameRoot) {
    const wolf::LoadResult result =
            wolf::loadGame(toStdString(env, gameId), toStdString(env, gameRoot));
    if (result.handle == 0) {
        jclass exceptionClass =
                env->FindClass("io/github/gdlbo/makerplay/runtime/api/WolfNativeLoadException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, result.error.c_str());
        }
        return 0L;
    }
    return static_cast<jlong>(result.handle);
}

JNIEXPORT void JNICALL
Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeDestroySession(JNIEnv*, jobject,
                                                                         jlong handle) {
    wolf::destroySession(static_cast<uint64_t>(handle));
}

JNIEXPORT void JNICALL
Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeSetPaused(JNIEnv*, jobject, jlong handle,
                                                                    jboolean paused) {
    wolf::setPaused(static_cast<uint64_t>(handle), paused == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeRequestExit(JNIEnv*, jobject,
                                                                      jlong handle) {
    wolf::requestExit(static_cast<uint64_t>(handle));
}

JNIEXPORT void JNICALL
Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeSetStaticFrame(
        JNIEnv* env, jobject, jlong handle, jbyteArray rgba, jint width, jint height) {
    if (rgba == nullptr || width <= 0 || height <= 0) return;
    const jsize size = env->GetArrayLength(rgba);
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    if (size > 0) {
        env->GetByteArrayRegion(rgba, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    }
    wolf::setStaticFrame(static_cast<uint64_t>(handle), bytes.data(), size, width, height);
}

JNIEXPORT void JNICALL
Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeRenderFrame(JNIEnv*, jobject,
                                                                      jlong handle, jint width,
                                                                      jint height) {
    wolf::renderFrame(static_cast<uint64_t>(handle), width, height);
}

JNIEXPORT void JNICALL
Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeSetInputState(
        JNIEnv* env, jobject, jlong handle, jintArray actions, jfloatArray axes) {
    std::vector<int32_t> actionValues;
    std::vector<float> axisValues;
    if (actions != nullptr) {
        const jsize size = env->GetArrayLength(actions);
        actionValues.resize(static_cast<size_t>(size));
        if (size > 0) {
            env->GetIntArrayRegion(actions, 0, size, actionValues.data());
        }
    }
    if (axes != nullptr) {
        const jsize size = env->GetArrayLength(axes);
        axisValues.resize(static_cast<size_t>(size));
        if (size > 0) {
            env->GetFloatArrayRegion(axes, 0, size, axisValues.data());
        }
    }
    wolf::setInputState(static_cast<uint64_t>(handle),
                        actionValues.empty() ? nullptr : actionValues.data(),
                        static_cast<int32_t>(actionValues.size()),
                        axisValues.empty() ? nullptr : axisValues.data(),
                        static_cast<int32_t>(axisValues.size()));
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeSerializeSave(JNIEnv* env, jobject,
                                                                        jlong handle,
                                                                        jstring slot) {
    const wolf::SaveResult result =
            wolf::serializeSave(static_cast<uint64_t>(handle), toStdString(env, slot));
    if (!result.error.empty()) {
        return nullptr;
    }
    return toByteArray(env, result.payload);
}

JNIEXPORT jboolean JNICALL
Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeRestoreSave(JNIEnv* env, jobject,
                                                                      jlong handle, jstring slot,
                                                                      jbyteArray payload) {
    std::vector<uint8_t> bytes;
    if (payload != nullptr) {
        const jsize size = env->GetArrayLength(payload);
        bytes.resize(static_cast<size_t>(size));
        if (size > 0) {
            env->GetByteArrayRegion(payload, 0, size,
                                    reinterpret_cast<jbyte*>(bytes.data()));
        }
    }
    return wolf::restoreSave(static_cast<uint64_t>(handle), toStdString(env, slot),
                             bytes.empty() ? nullptr : bytes.data(),
                             static_cast<int32_t>(bytes.size()))
                   ? JNI_TRUE
                   : JNI_FALSE;
}

JNIEXPORT jdoubleArray JNICALL
Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeDiagnosticsSnapshot(JNIEnv* env, jobject,
                                                                              jlong handle) {
    const wolf::Diagnostics snapshot = wolf::diagnosticsSnapshot(static_cast<uint64_t>(handle));
    const jdoubleArray array = env->NewDoubleArray(5);
    if (array != nullptr) {
        const jdouble values[5] = {static_cast<jdouble>(snapshot.framesRendered),
                                   snapshot.averageFrameMillis,
                                   static_cast<jdouble>(snapshot.mapsParsed),
                                   static_cast<jdouble>(snapshot.eventsExecuted),
                                   static_cast<jdouble>(snapshot.audioStreamsActive)};
        env->SetDoubleArrayRegion(array, 0, 5, values);
    }
    return array;
}

JNIEXPORT jstring JNICALL
Java_io_github_gdlbo_makerplay_runtime_wolf_WolfNativeJni_nativeLastError(JNIEnv* env, jobject,
                                                                    jlong handle) {
    const char* error = wolf::lastError(static_cast<uint64_t>(handle));
    return error == nullptr ? nullptr : env->NewStringUTF(error);
}

}  // extern "C"
