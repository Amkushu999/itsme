/**
 * gst_pipeline.cpp — FaceGate GStreamer JNI bridge
 *
 * Implements:
 *   GStreamerPipeline.nativeInit(Context)
 *   GStreamerPipeline.nativeCreatePipeline(url, FrameCallback) → Long handle
 *   GStreamerPipeline.nativeDestroyPipeline(handle)
 *   GStreamerPipeline.nativeCleanup()
 *
 * Pipeline topology (built in nativeCreatePipeline):
 *
 *   RTSP:
 *     rtspsrc location=$URL protocols=tcp latency=200
 *       → rtpjitterbuffer drop-on-latency=true
 *       → decodebin
 *       → videoconvert
 *       → video/x-raw,format=I420
 *       → appsink name=sink max-buffers=2 drop=true emit-signals=true
 *
 *   HTTP/HLS (auto-detected by decodebin via urisourcebin):
 *     urisourcebin uri=$URL
 *       → decodebin
 *       → videoconvert
 *       → video/x-raw,format=I420
 *       → appsink name=sink max-buffers=2 drop=true emit-signals=true
 *
 * On each appsink new-sample signal:
 *   1. Map the GstBuffer to a GstMapInfo (read mode, no copy).
 *   2. Call back into Java via FrameCallback.onFrameAvailable(byte[], w, h, pts).
 *   3. Unmap immediately — zero copy path for the buffer.
 *
 * Prerequisites:
 *   - GStreamer Android SDK present at GSTREAMER_ROOT_ANDROID
 *     (set gstreamer.dir in local.properties or GSTREAMER_ROOT env var).
 *   - CMakeLists.txt links gstreamer_android + required plugins.
 *
 * Threading:
 *   - GStreamer streaming thread calls new_sample callback.
 *   - Java FrameCallback.onFrameAvailable is invoked from that thread.
 *   - SurfaceRouter.onFrameAvailable must be non-blocking.
 */

#include <gst/gst.h>
#include <gst/app/gstappsink.h>
#include <gst/video/video.h>
#include <gst/android/gstandroidjni.h>
#include <android/log.h>
#include <jni.h>
#include <string>
#include <memory>
#include <atomic>

#define LOG_TAG "FaceGate_GST"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ── Java method IDs (cached at init time) ────────────────────────────────────

static JavaVM* g_jvm = nullptr;

struct JavaCallbackRefs {
    jobject   callback_obj  = nullptr;
    jmethodID onFrameMethod = nullptr;
};

// ── Pipeline context ─────────────────────────────────────────────────────────

struct PipelineCtx {
    GstElement*       pipeline  = nullptr;
    GstElement*       appsink   = nullptr;
    GMainLoop*        loop      = nullptr;
    GThread*          gst_thread = nullptr;
    JavaCallbackRefs  java;
    std::atomic<bool> running{false};
    int               video_width  = 0;
    int               video_height = 0;
};

// ── GLib main-loop thread ────────────────────────────────────────────────────

static gpointer gst_main_loop_thread(gpointer data) {
    auto* ctx = reinterpret_cast<PipelineCtx*>(data);
    LOGD("GStreamer main loop starting");
    g_main_loop_run(ctx->loop);
    LOGD("GStreamer main loop exited");
    return nullptr;
}

// ── appsink new-sample callback ───────────────────────────────────────────────

static GstFlowReturn on_new_sample(GstAppSink* appsink, gpointer user_data) {
    auto* ctx = reinterpret_cast<PipelineCtx*>(user_data);
    if (!ctx->running) return GST_FLOW_OK;

    GstSample* sample = gst_app_sink_pull_sample(appsink);
    if (!sample) return GST_FLOW_ERROR;

    GstCaps*    caps   = gst_sample_get_caps(sample);
    GstBuffer*  buffer = gst_sample_get_buffer(sample);
    GstMapInfo  map;

    // Read video dimensions from caps (I420 format)
    if (caps && (ctx->video_width == 0)) {
        GstStructure* s = gst_caps_get_structure(caps, 0);
        gst_structure_get_int(s, "width",  &ctx->video_width);
        gst_structure_get_int(s, "height", &ctx->video_height);
        LOGD("Video dimensions: %dx%d", ctx->video_width, ctx->video_height);
    }

    if (!gst_buffer_map(buffer, &map, GST_MAP_READ)) {
        gst_sample_unref(sample);
        return GST_FLOW_ERROR;
    }

    // ── Call Java FrameCallback.onFrameAvailable(byte[], width, height, pts) ──
    JNIEnv* env = nullptr;
    bool attached = false;
    jint attach_result = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (attach_result == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
        else { gst_buffer_unmap(buffer, &map); gst_sample_unref(sample); return GST_FLOW_OK; }
    }

    if (env && ctx->java.callback_obj && ctx->java.onFrameMethod) {
        const int w = ctx->video_width;
        const int h = ctx->video_height;
        const jlong pts = (jlong)GST_BUFFER_PTS(buffer);
        const jsize data_len = (jsize)map.size;

        jbyteArray jdata = env->NewByteArray(data_len);
        if (jdata) {
            env->SetByteArrayRegion(jdata, 0, data_len,
                reinterpret_cast<const jbyte*>(map.data));
            env->CallVoidMethod(ctx->java.callback_obj,
                ctx->java.onFrameMethod,
                jdata, (jint)w, (jint)h, pts);
            env->DeleteLocalRef(jdata);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    if (attached) g_jvm->DetachCurrentThread();
    gst_buffer_unmap(buffer, &map);
    gst_sample_unref(sample);
    return GST_FLOW_OK;
}

// ── Bus watch (error / EOS handling) ─────────────────────────────────────────

static gboolean on_bus_message(GstBus* bus, GstMessage* msg, gpointer data) {
    auto* ctx = reinterpret_cast<PipelineCtx*>(data);
    switch (GST_MESSAGE_TYPE(msg)) {
        case GST_MESSAGE_ERROR: {
            GError* err = nullptr;
            gchar*  dbg = nullptr;
            gst_message_parse_error(msg, &err, &dbg);
            LOGE("GStreamer error: %s | %s", err ? err->message : "?", dbg ? dbg : "?");
            g_error_free(err);
            g_free(dbg);
            // Attempt auto-restart: set pipeline to NULL then PLAYING
            gst_element_set_state(ctx->pipeline, GST_STATE_NULL);
            gst_element_set_state(ctx->pipeline, GST_STATE_PLAYING);
            break;
        }
        case GST_MESSAGE_EOS:
            LOGD("GStreamer EOS — seeking to beginning");
            gst_element_seek_simple(ctx->pipeline, GST_FORMAT_TIME,
                (GstSeekFlags)(GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_KEY_UNIT), 0);
            break;
        case GST_MESSAGE_STATE_CHANGED:
            if (GST_MESSAGE_SRC(msg) == GST_OBJECT(ctx->pipeline)) {
                GstState old_state, new_state;
                gst_message_parse_state_changed(msg, &old_state, &new_state, nullptr);
                LOGD("Pipeline state: %s → %s",
                    gst_element_state_get_name(old_state),
                    gst_element_state_get_name(new_state));
            }
            break;
        default:
            break;
    }
    return TRUE;
}

// ── Pipeline factory ──────────────────────────────────────────────────────────

/**
 * Build pipeline description based on the URL scheme.
 * RTSP: use rtspsrc for reliable TCP transport.
 * Everything else: use urisourcebin which handles HTTP/HLS/file/etc.
 */
static std::string build_pipeline_str(const std::string& url) {
    bool is_rtsp = (url.rfind("rtsp", 0) == 0) || (url.rfind("rtsps", 0) == 0);

    if (is_rtsp) {
        return "rtspsrc location=" + url + " protocols=tcp latency=200 "
               "! rtpjitterbuffer drop-on-latency=true "
               "! decodebin "
               "! videoconvert "
               "! video/x-raw,format=I420 "
               "! appsink name=sink max-buffers=2 drop=true emit-signals=true sync=false";
    } else {
        // HLS, HTTP progressive, file, SRT, etc.
        return "urisourcebin uri=" + url + " "
               "! decodebin "
               "! videoconvert "
               "! video/x-raw,format=I420 "
               "! appsink name=sink max-buffers=2 drop=true emit-signals=true sync=false";
    }
}

// ── JNI implementations ───────────────────────────────────────────────────────

extern "C" {

/**
 * GStreamerPipeline.nativeInit(Context)
 * Called once from Kotlin before any pipeline is created.
 */
JNIEXPORT void JNICALL
Java_com_itsme_amkush_gstreamer_GStreamerPipeline_nativeInit(
        JNIEnv* env, jclass /*cls*/, jobject context) {

    env->GetJavaVM(&g_jvm);

    // Initialize GStreamer via the Android JNI helper provided by GStreamer SDK
    if (!gst_is_initialized()) {
        gst_android_init(env, context);   // provided by gstreamer_android lib
        LOGI("GStreamer initialized");
    }
}

/**
 * GStreamerPipeline.nativeCreatePipeline(url, FrameCallback) → Long (PipelineCtx*)
 */
JNIEXPORT jlong JNICALL
Java_com_itsme_amkush_gstreamer_GStreamerPipeline_nativeCreatePipeline(
        JNIEnv* env, jclass /*cls*/,
        jstring j_url, jobject j_callback) {

    const char* url_cstr = env->GetStringUTFChars(j_url, nullptr);
    std::string url(url_cstr);
    env->ReleaseStringUTFChars(j_url, url_cstr);

    LOGI("Creating pipeline for URL: %s", url.c_str());

    // ── Find Java callback method ──
    jclass cb_class = env->GetObjectClass(j_callback);
    jmethodID on_frame_mid = env->GetMethodID(cb_class, "onFrameAvailable", "([BIIJ)V");
    if (!on_frame_mid) {
        LOGE("Cannot find FrameCallback.onFrameAvailable method");
        return 0;
    }

    // ── Allocate context ──
    auto* ctx = new PipelineCtx();
    ctx->java.callback_obj  = env->NewGlobalRef(j_callback);
    ctx->java.onFrameMethod = on_frame_mid;
    ctx->running = true;

    // ── Build pipeline ──
    std::string pipeline_str = build_pipeline_str(url);
    LOGD("Pipeline: %s", pipeline_str.c_str());

    GError* error = nullptr;
    ctx->pipeline = gst_parse_launch(pipeline_str.c_str(), &error);
    if (error || !ctx->pipeline) {
        LOGE("gst_parse_launch failed: %s", error ? error->message : "unknown");
        g_clear_error(&error);
        env->DeleteGlobalRef(ctx->java.callback_obj);
        delete ctx;
        return 0;
    }

    // ── Get appsink and set callbacks ──
    ctx->appsink = gst_bin_get_by_name(GST_BIN(ctx->pipeline), "sink");
    if (!ctx->appsink) {
        LOGE("appsink element not found in pipeline");
        gst_object_unref(ctx->pipeline);
        env->DeleteGlobalRef(ctx->java.callback_obj);
        delete ctx;
        return 0;
    }

    GstAppSinkCallbacks appsink_cbs = {
        nullptr,                // eos
        nullptr,                // new_preroll
        on_new_sample,          // new_sample
        nullptr,                // new_event
        { nullptr, nullptr, nullptr }  // padding
    };
    gst_app_sink_set_callbacks(GST_APP_SINK(ctx->appsink),
                               &appsink_cbs, ctx, nullptr);

    // ── Attach bus watch for errors/EOS ──
    GstBus* bus = gst_element_get_bus(ctx->pipeline);
    gst_bus_add_watch(bus, on_bus_message, ctx);
    gst_object_unref(bus);

    // ── Start GLib main loop (needed for bus watch) ──
    ctx->loop = g_main_loop_new(nullptr, FALSE);
    ctx->gst_thread = g_thread_new("gst-main",
        (GThreadFunc)gst_main_loop_thread, ctx);

    // ── Start playback ──
    GstStateChangeReturn ret = gst_element_set_state(ctx->pipeline, GST_STATE_PLAYING);
    if (ret == GST_STATE_CHANGE_FAILURE) {
        LOGE("Failed to set pipeline to PLAYING");
        g_main_loop_quit(ctx->loop);
        g_thread_join(ctx->gst_thread);
        g_main_loop_unref(ctx->loop);
        gst_object_unref(ctx->appsink);
        gst_object_unref(ctx->pipeline);
        env->DeleteGlobalRef(ctx->java.callback_obj);
        delete ctx;
        return 0;
    }

    LOGI("Pipeline started successfully");
    return reinterpret_cast<jlong>(ctx);
}

/**
 * GStreamerPipeline.nativeDestroyPipeline(handle)
 */
JNIEXPORT void JNICALL
Java_com_itsme_amkush_gstreamer_GStreamerPipeline_nativeDestroyPipeline(
        JNIEnv* env, jclass /*cls*/, jlong handle) {

    if (handle == 0) return;
    auto* ctx = reinterpret_cast<PipelineCtx*>(handle);

    LOGD("Destroying pipeline");
    ctx->running = false;

    // Stop pipeline
    gst_element_set_state(ctx->pipeline, GST_STATE_NULL);

    // Stop main loop
    if (ctx->loop && g_main_loop_is_running(ctx->loop)) {
        g_main_loop_quit(ctx->loop);
    }
    if (ctx->gst_thread) {
        g_thread_join(ctx->gst_thread);
        ctx->gst_thread = nullptr;
    }
    if (ctx->loop) {
        g_main_loop_unref(ctx->loop);
        ctx->loop = nullptr;
    }

    // Release elements
    if (ctx->appsink) { gst_object_unref(ctx->appsink); ctx->appsink = nullptr; }
    if (ctx->pipeline) { gst_object_unref(ctx->pipeline); ctx->pipeline = nullptr; }

    // Release Java refs
    if (ctx->java.callback_obj) {
        env->DeleteGlobalRef(ctx->java.callback_obj);
        ctx->java.callback_obj = nullptr;
    }

    delete ctx;
    LOGD("Pipeline destroyed");
}

/**
 * GStreamerPipeline.nativeCleanup()
 */
JNIEXPORT void JNICALL
Java_com_itsme_amkush_gstreamer_GStreamerPipeline_nativeCleanup(
        JNIEnv* /*env*/, jclass /*cls*/) {
    LOGI("GStreamer cleanup complete");
}

} // extern "C"
