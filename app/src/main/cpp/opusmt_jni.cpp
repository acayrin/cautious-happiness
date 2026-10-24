#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include <ctranslate2/translator.h>
#include <sentencepiece_processor.h>

namespace {

struct Engine {
  ctranslate2::Translator translator;
  sentencepiece::SentencePieceProcessor src;
  sentencepiece::SentencePieceProcessor tgt;

  Engine(const std::string& model_dir, const std::string& src_spm, const std::string& tgt_spm)
    : translator(model_dir, ctranslate2::Device::CPU)
  {
    if (!src.Load(src_spm).ok() || !tgt.Load(tgt_spm).ok())
      throw std::runtime_error("failed to load sentencepiece models");
  }

  std::string translate(const std::string& text) {
    std::vector<std::string> pieces;
    if (!src.Encode(text, &pieces).ok())
      throw std::runtime_error("tokenization failed");

    ctranslate2::TranslationOptions options;
    options.beam_size = 1; // greedy: 4x faster than beam-4, negligible quality loss on short lines
    options.max_decoding_length = 96;
    options.length_penalty = 0.6f;

    auto results = translator.translate_batch({pieces}, options);
    if (results.empty() || results[0].hypotheses.empty())
      throw std::runtime_error("no hypothesis");

    std::string out;
    if (!tgt.Decode(results[0].hypotheses[0], &out).ok())
      throw std::runtime_error("detokenization failed");
    return out;
  }
};

Engine* to_engine(jlong handle) {
  return reinterpret_cast<Engine*>(handle);
}

jlong nativeCreate(JNIEnv* env, jclass, jstring model_dir, jstring src_spm, jstring tgt_spm) {
  try {
    std::string dir = env->GetStringUTFChars(model_dir, nullptr);
    std::string src = env->GetStringUTFChars(src_spm, nullptr);
    std::string tgt = env->GetStringUTFChars(tgt_spm, nullptr);
    return reinterpret_cast<jlong>(new Engine(dir, src, tgt));
  } catch (const std::exception& e) {
    return 0;
  }
}

jobjectArray nativeTranslate(JNIEnv* env, jclass, jlong handle, jobjectArray texts) {
  Engine* engine = to_engine(handle);
  if (engine == nullptr) return nullptr;
  try {
    jsize n = env->GetArrayLength(texts);
    std::vector<std::string> inputs;
    inputs.reserve(n);
    for (jsize i = 0; i < n; ++i) {
      auto str = (jstring)env->GetObjectArrayElement(texts, i);
      inputs.emplace_back(env->GetStringUTFChars(str, nullptr));
      env->DeleteLocalRef(str);
    }

    // Batch translate: one translate_batch call for the whole set.
    std::vector<std::vector<std::string>> batch;
    batch.reserve(inputs.size());
    for (auto& text : inputs) {
      std::vector<std::string> pieces;
      if (!engine->src.Encode(text, &pieces).ok())
        throw std::runtime_error("tokenization failed");
      batch.push_back(std::move(pieces));
    }

    ctranslate2::TranslationOptions options;
    options.beam_size = 1; // greedy: 4x faster than beam-4, negligible quality loss on short lines
    options.max_decoding_length = 96;
    options.length_penalty = 0.6f;

    auto results = engine->translator.translate_batch(batch, options);

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(results.size(), stringClass, nullptr);
    for (size_t i = 0; i < results.size(); ++i) {
      if (!results[i].hypotheses.empty()) {
        std::string decoded;
        if (engine->tgt.Decode(results[i].hypotheses[0], &decoded).ok()) {
          jstring js = env->NewStringUTF(decoded.c_str());
          env->SetObjectArrayElement(out, i, js);
          env->DeleteLocalRef(js);
        }
      }
    }
    return out;
  } catch (const std::exception&) {
    return nullptr;
  }
}

void nativeDestroy(JNIEnv*, jclass, jlong handle) {
  delete to_engine(handle);
}

const JNINativeMethod kMethods[] = {
  {"nativeCreate", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)J", (void*)nativeCreate},
  {"nativeTranslate", "(J[Ljava/lang/String;)[Ljava/lang/String;", (void*)nativeTranslate},
  {"nativeDestroy", "(J)V", (void*)nativeDestroy},
};

}  // namespace

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
    return JNI_ERR;
  jclass cls = env->FindClass("com/overlaytranslator/translate/OpusMtNative");
  if (cls == nullptr) return JNI_ERR;
  if (env->RegisterNatives(cls, kMethods, 3) != 0) return JNI_ERR;
  return JNI_VERSION_1_6;
}
