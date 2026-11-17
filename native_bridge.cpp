#include <algorithm>
#include <climits>
#include <cstdint>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include <jni.h>
#include <llama.h>
#include <mtmd.h>
#include <mtmd-helper.h>

namespace {

llama_model *model = nullptr;
llama_context *context = nullptr;
mtmd_context *vision_context = nullptr;
bool backend_initialized = false;

void release_model() {
    if (vision_context != nullptr) {
        mtmd_free(vision_context);
        vision_context = nullptr;
    }
    if (context != nullptr) {
        llama_free(context);
        context = nullptr;
    }
    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }
}

void throw_state_error(JNIEnv *env, const std::string &message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) {
        env->ThrowNew(exception, message.c_str());
    }
}

void append_utf8(std::string &output, uint32_t code_point) {
    if (code_point <= 0x7f) {
        output.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7ff) {
        output.push_back(static_cast<char>(0xc0 | (code_point >> 6)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else if (code_point <= 0xffff) {
        output.push_back(static_cast<char>(0xe0 | (code_point >> 12)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else {
        output.push_back(static_cast<char>(0xf0 | (code_point >> 18)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    }
}

std::string read_string(JNIEnv *env, jstring value) {
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    const jsize length = env->GetStringLength(value);
    std::string result;
    for (jsize index = 0; index < length; ++index) {
        uint32_t code_point = chars[index];
        if (code_point >= 0xd800 && code_point <= 0xdbff &&
            index + 1 < length && chars[index + 1] >= 0xdc00 && chars[index + 1] <= 0xdfff) {
            code_point = 0x10000 + ((code_point - 0xd800) << 10) +
                (static_cast<uint32_t>(chars[++index]) - 0xdc00);
        } else if (code_point >= 0xd800 && code_point <= 0xdfff) {
            code_point = 0xfffd;
        }
        append_utf8(result, code_point);
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

std::u16string utf8_to_utf16(const std::string &input) {
    std::u16string output;
    for (size_t index = 0; index < input.size();) {
        const auto first = static_cast<uint8_t>(input[index]);
        uint32_t code_point = 0xfffd;
        size_t length = 1;
        if (first < 0x80) {
            code_point = first;
        } else {
            size_t expected = 0;
            if ((first & 0xe0) == 0xc0) {
                code_point = first & 0x1f;
                expected = 2;
            } else if ((first & 0xf0) == 0xe0) {
                code_point = first & 0x0f;
                expected = 3;
            } else if ((first & 0xf8) == 0xf0) {
                code_point = first & 0x07;
                expected = 4;
            }
            if (expected != 0 && index + expected <= input.size()) {
                bool valid = true;
                for (size_t offset = 1; offset < expected; ++offset) {
                    const auto continuation = static_cast<uint8_t>(input[index + offset]);
                    if ((continuation & 0xc0) != 0x80) {
                        valid = false;
                        break;
                    }
                    code_point = (code_point << 6) | (continuation & 0x3f);
                }
                const bool overlong = (expected == 2 && code_point < 0x80) ||
                    (expected == 3 && code_point < 0x800) ||
                    (expected == 4 && code_point < 0x10000);
                if (valid && !overlong && code_point <= 0x10ffff &&
                    !(code_point >= 0xd800 && code_point <= 0xdfff)) {
                    length = expected;
                } else {
                    code_point = 0xfffd;
                }
            } else {
                code_point = 0xfffd;
            }
        }
        if (code_point <= 0xffff) {
            output.push_back(static_cast<char16_t>(code_point));
        } else {
            code_point -= 0x10000;
            output.push_back(static_cast<char16_t>(0xd800 + (code_point >> 10)));
            output.push_back(static_cast<char16_t>(0xdc00 + (code_point & 0x3ff)));
        }
        index += length;
    }
    return output;
}

bool encode_prompt(const std::vector<llama_chat_message> &messages, std::string &prompt) {
    const char *chat_template = llama_model_chat_template(model, nullptr);
    if (chat_template == nullptr) {
        return false;
    }

    const int32_t required = llama_chat_apply_template(
        chat_template, messages.data(), messages.size(), true, nullptr, 0);
    if (required <= 0) {
        return false;
    }

    std::vector<char> buffer(static_cast<size_t>(required) + 1);
    const int32_t written = llama_chat_apply_template(
        chat_template, messages.data(), messages.size(), true, buffer.data(),
        static_cast<int32_t>(buffer.size()));
    if (written <= 0 || written > required) {
        return false;
    }
    prompt.assign(buffer.data(), static_cast<size_t>(written));
    return true;
}

bool tokenize_prompt(
    const llama_vocab *vocab,
    const std::string &prompt,
    std::vector<llama_token> &tokens) {
    if (prompt.size() > static_cast<size_t>(INT32_MAX)) {
        return false;
    }
    const int32_t prompt_length = static_cast<int32_t>(prompt.size());
    int32_t count = llama_tokenize(vocab, prompt.c_str(), prompt_length, nullptr, 0, true, true);
    if (count == INT32_MIN) {
        return false;
    }
    if (count < 0) {
        count = -count;
    }
    tokens.resize(static_cast<size_t>(count));
    if (count == 0) {
        return false;
    }
    const int32_t actual = llama_tokenize(
        vocab, prompt.c_str(), prompt_length, tokens.data(), count, true, true);
    if (actual <= 0 || actual > count) {
        return false;
    }
    tokens.resize(static_cast<size_t>(actual));
    return true;
}

bool evaluate_prompt(const std::vector<llama_token> &tokens) {
    llama_batch_ext *batch = llama_batch_ext_init(context);
    if (batch == nullptr) {
        return false;
    }

    const uint32_t max_batch = std::max(1U, llama_n_batch(context));
    bool succeeded = true;
    for (size_t start = 0; start < tokens.size() && succeeded; start += max_batch) {
        llama_batch_ext_clear(batch);
        const size_t end = std::min(tokens.size(), start + max_batch);
        for (size_t index = start; index < end; ++index) {
            const int32_t batch_index = llama_batch_ext_add_token(batch, 0, tokens[index]);
            const llama_pos position = static_cast<llama_pos>(index);
            if (batch_index < 0 ||
                !llama_batch_ext_set_pos(batch, batch_index, &position) ||
                !llama_batch_ext_set_output_logits(batch, batch_index, index + 1 == tokens.size())) {
                succeeded = false;
                break;
            }
        }
        if (succeeded && llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            succeeded = false;
        }
    }
    llama_batch_ext_free(batch);
    return succeeded;
}

std::string token_piece(const llama_vocab *vocab, llama_token token) {
    std::vector<char> buffer(128);
    int32_t length = llama_token_to_piece(vocab, token, buffer.data(), buffer.size(), 0, false);
    if (length < 0) {
        buffer.resize(static_cast<size_t>(-length));
        length = llama_token_to_piece(vocab, token, buffer.data(), buffer.size(), 0, false);
    }
    if (length <= 0) {
        return {};
    }
    return {buffer.data(), static_cast<size_t>(length)};
}

struct BitmapList {
    std::vector<mtmd_bitmap *> values;

    ~BitmapList() {
        for (auto *bitmap : values) {
            mtmd_bitmap_free(bitmap);
        }
    }

    std::vector<const mtmd_bitmap *> const_values() const {
        return {values.begin(), values.end()};
    }
};

struct InputChunksDeleter {
    void operator()(mtmd_input_chunks *chunks) const {
        if (chunks != nullptr) {
            mtmd_input_chunks_free(chunks);
        }
    }
};

bool evaluate_multimodal_prompt(
    const std::string &prompt,
    const std::vector<std::string> &image_paths,
    llama_pos &new_n_past) {
    BitmapList bitmaps;
    for (const auto &path : image_paths) {
        if (path.empty()) {
            continue;
        }
        auto loaded = mtmd_helper_bitmap_init_from_file(
            vision_context,
            path.c_str(),
            false,
            mtmd_helper_init_opt_default());
        if (loaded.video_ctx != nullptr) {
            mtmd_helper_video_free(loaded.video_ctx);
        }
        if (loaded.bitmap == nullptr) {
            return false;
        }
        bitmaps.values.push_back(loaded.bitmap);
    }

    mtmd_input_text input_text{prompt.data(), prompt.size(), true, true};
    std::unique_ptr<mtmd_input_chunks, InputChunksDeleter> chunks(
        mtmd_input_chunks_init());
    if (!chunks) {
        return false;
    }

    const auto bitmap_pointers = bitmaps.const_values();
    if (mtmd_tokenize(
            vision_context,
            chunks.get(),
            &input_text,
            bitmap_pointers.data(),
            bitmap_pointers.size()) != 0) {
        return false;
    }

    return mtmd_helper_eval_chunks(
        vision_context,
        context,
        chunks.get(),
        0,
        0,
        static_cast<int32_t>(llama_n_batch(context)),
        true,
        &new_n_past) == 0;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_localchat_inference_NativeLlama_loadModel(
    JNIEnv *env, jobject, jstring model_path, jstring projector_path) {
    release_model();
    if (!backend_initialized) {
        llama_backend_init();
        backend_initialized = true;
    }

    const std::string path = read_string(env, model_path);
    const std::string projector = read_string(env, projector_path);
    if (path.empty()) {
        return JNI_FALSE;
    }

    llama_model_params model_params = llama_model_default_params();
    model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        return JNI_FALSE;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = 4096;
    context_params.n_batch = 1024;
    context_params.n_ubatch = 1024;
    context = llama_init_from_model(model, context_params);
    if (context == nullptr) {
        release_model();
        return JNI_FALSE;
    }

    if (!projector.empty()) {
        mtmd_context_params vision_params = mtmd_context_params_default();
        vision_params.use_gpu = false;
        vision_params.n_threads = 4;
        vision_params.image_max_tokens = 1024;
        vision_context = mtmd_init_from_file(projector.c_str(), model, vision_params);
        if (vision_context == nullptr || !mtmd_support_vision(vision_context)) {
            release_model();
            return JNI_FALSE;
        }
    }

    const int processor_count = static_cast<int>(std::thread::hardware_concurrency());
    llama_set_n_threads(context, std::max(1, std::min(4, processor_count)), 4);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_localchat_inference_NativeLlama_generate(
    JNIEnv *env,
    jobject,
    jobjectArray roles,
    jobjectArray contents,
    jobjectArray image_paths,
    jint max_tokens) {
    if (model == nullptr || context == nullptr) {
        throw_state_error(env, "Load a GGUF model before generating a response.");
        return nullptr;
    }

    const jsize message_count = env->GetArrayLength(roles);
    if (message_count != env->GetArrayLength(contents) ||
        message_count != env->GetArrayLength(image_paths) || message_count == 0) {
        throw_state_error(env, "The conversation has no valid messages.");
        return nullptr;
    }

    std::vector<std::string> role_strings;
    std::vector<std::string> content_strings;
    std::vector<std::string> image_path_strings;
    std::vector<llama_chat_message> chat_messages;
    role_strings.reserve(static_cast<size_t>(message_count));
    content_strings.reserve(static_cast<size_t>(message_count));
    image_path_strings.reserve(static_cast<size_t>(message_count));
    chat_messages.reserve(static_cast<size_t>(message_count));

    for (jsize index = 0; index < message_count; ++index) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, index));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, index));
        auto image_path = static_cast<jstring>(env->GetObjectArrayElement(image_paths, index));
        role_strings.push_back(read_string(env, role));
        content_strings.push_back(read_string(env, content));
        image_path_strings.push_back(read_string(env, image_path));
        if (vision_context != nullptr && !image_path_strings.back().empty()) {
            content_strings.back().append("\n");
            content_strings.back().append(mtmd_default_marker());
        }
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
        env->DeleteLocalRef(image_path);
    }
    for (jsize index = 0; index < message_count; ++index) {
        chat_messages.push_back({
            role_strings[static_cast<size_t>(index)].c_str(),
            content_strings[static_cast<size_t>(index)].c_str(),
        });
    }

    std::string prompt;
    if (!encode_prompt(chat_messages, prompt)) {
        throw_state_error(env, "This GGUF model has no supported chat template.");
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(context), true);
    llama_pos n_past = 0;
    const bool has_vision_input = vision_context != nullptr &&
        std::any_of(image_path_strings.begin(), image_path_strings.end(), [](const auto &path) {
            return !path.empty();
        });
    if (has_vision_input) {
        if (!evaluate_multimodal_prompt(prompt, image_path_strings, n_past)) {
            throw_state_error(env, "The vision model could not process this image and chat.");
            return nullptr;
        }
    } else {
        const llama_vocab *vocab = llama_model_get_vocab(model);
        std::vector<llama_token> prompt_tokens;
        if (!tokenize_prompt(vocab, prompt, prompt_tokens)) {
            throw_state_error(env, "Could not tokenize the conversation.");
            return nullptr;
        }
        if (prompt_tokens.size() + 1 >= llama_n_ctx(context)) {
            throw_state_error(env, "The chat history exceeds this model's 4096-token context.");
            return nullptr;
        }
        if (!evaluate_prompt(prompt_tokens)) {
            throw_state_error(env, "The model could not evaluate this conversation.");
            return nullptr;
        }
        n_past = static_cast<llama_pos>(prompt_tokens.size());
    }

    if (n_past + 1 >= static_cast<llama_pos>(llama_n_ctx(context))) {
        throw_state_error(env, "The chat history and image exceed this model's 4096-token context.");
        return nullptr;
    }

    const llama_vocab *vocab = llama_model_get_vocab(model);
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (sampler == nullptr) {
        throw_state_error(env, "Could not initialize the model sampler.");
        return nullptr;
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    std::string response;
    const int32_t output_limit = std::max(1, std::min(1024, static_cast<int>(max_tokens)));
    const int32_t remaining = static_cast<int32_t>(llama_n_ctx(context) - n_past);
    const int32_t token_limit = std::min(output_limit, remaining);
    llama_batch_ext *batch = llama_batch_ext_init(context);
    if (batch == nullptr) {
        llama_sampler_free(sampler);
        throw_state_error(env, "Could not allocate a model batch.");
        return nullptr;
    }

    for (int32_t generated = 0; generated < token_limit; ++generated) {
        const llama_token token = llama_sampler_sample(sampler, context, -1);
        llama_sampler_accept(sampler, token);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        response += token_piece(vocab, token);

        llama_batch_ext_clear(batch);
        const int32_t batch_index = llama_batch_ext_add_token(batch, 0, token);
        const llama_pos position = n_past + generated;
        if (batch_index < 0 ||
            !llama_batch_ext_set_pos(batch, batch_index, &position) ||
            !llama_batch_ext_set_output_logits(batch, batch_index, true) ||
            llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            llama_batch_ext_free(batch);
            llama_sampler_free(sampler);
            throw_state_error(env, "On-device token generation failed.");
            return nullptr;
        }
    }

    llama_batch_ext_free(batch);
    llama_sampler_free(sampler);
    const std::u16string utf16_response = utf8_to_utf16(response);
    return env->NewString(
        reinterpret_cast<const jchar *>(utf16_response.data()),
        static_cast<jsize>(utf16_response.size()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_localchat_inference_NativeLlama_releaseModel(JNIEnv *, jobject) {
    release_model();
}
