// JNI shim around whisper.cpp for on-device speech-to-text.
#include <jni.h>

#include <string>
#include <vector>

#include "whisper.h"

namespace {

struct Asr {
  whisper_context* ctx;
  std::string lang;
};

jlong nativeCreate(JNIEnv* env, jclass, jstring model_path, jstring lang) {
  const char* path = env->GetStringUTFChars(model_path, nullptr);
  const char* language = env->GetStringUTFChars(lang, nullptr);
  whisper_context_params cparams = whisper_context_default_params();
  cparams.use_gpu = false;
  whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
  std::string l = language ? language : "auto";
  env->ReleaseStringUTFChars(model_path, path);
  env->ReleaseStringUTFChars(lang, language);
  if (ctx == nullptr) return 0;
  return reinterpret_cast<jlong>(new Asr{ctx, std::move(l)});
}

jstring nativeTranscribe(JNIEnv* env, jclass, jlong handle, jfloatArray samples) {
  Asr* asr = reinterpret_cast<Asr*>(handle);
  if (asr == nullptr || asr->ctx == nullptr) return nullptr;
  jsize n = env->GetArrayLength(samples);
  if (n <= 0) return nullptr;
  std::vector<float> buf(static_cast<size_t>(n));
  env->GetFloatArrayRegion(samples, 0, n, buf.data());

  // Greedy + 2 threads: speed over accuracy, keeps CPU headroom for translation.
  whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
  params.language = asr->lang.c_str();
  params.n_threads = 2;
  params.print_realtime = false;
  params.print_progress = false;
  params.print_special = false;
  params.print_timestamps = false;
  params.no_timestamps = true;
  params.no_context = true;  // windows are disjoint; avoid bleeding context
  params.suppress_blank = true;
  params.translate = false;

  if (whisper_full(asr->ctx, params, buf.data(), static_cast<int>(n)) != 0)
    return nullptr;

  std::string out;
  bool speech = false;
  const int nseg = whisper_full_n_segments(asr->ctx);
  for (int i = 0; i < nseg; ++i) {
    // Music/silence windows make whisper hallucinate text; treat segments it
    // itself flags as "no speech" as an empty result (feeds the engine backoff).
    if (whisper_full_get_segment_no_speech_prob(asr->ctx, i) <= 0.6f) speech = true;
    const char* t = whisper_full_get_segment_text(asr->ctx, i);
    if (t != nullptr) out += t;
  }
  if (!speech) return nullptr;
  const size_t b = out.find_first_not_of(" \t\n\r");
  if (b == std::string::npos) return nullptr;
  const size_t e = out.find_last_not_of(" \t\n\r");
  return env->NewStringUTF(out.substr(b, e - b + 1).c_str());
}

void nativeDestroy(JNIEnv*, jclass, jlong handle) {
  Asr* asr = reinterpret_cast<Asr*>(handle);
  if (asr == nullptr) return;
  if (asr->ctx != nullptr) whisper_free(asr->ctx);
  delete asr;
}

const JNINativeMethod kMethods[] = {
    {"nativeCreate", "(Ljava/lang/String;Ljava/lang/String;)J", (void*)nativeCreate},
    {"nativeTranscribe", "(J[F)Ljava/lang/String;", (void*)nativeTranscribe},
    {"nativeDestroy", "(J)V", (void*)nativeDestroy},
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
    return JNI_ERR;
  jclass cls = env->FindClass("com/overlaytranslator/audio/WhisperNative");
  if (cls == nullptr) return JNI_ERR;
  if (env->RegisterNatives(cls, kMethods, 3) != 0) return JNI_ERR;
  return JNI_VERSION_1_6;
}
