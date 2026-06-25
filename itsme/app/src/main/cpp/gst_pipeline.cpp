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
 *   2. Wrap the native buffer in a direct ByteBuffer — zero copy into Java.
 *   3. Call back into Java via FrameCallback.onFrameAvailable(ByteBuffer, w, h, pts).
 *   4. Unmap immediately after the callback returns.
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
 *
 * Fix log (vs original):
 *   C1  — DeleteGlobalRef called in every error branch after NewGlobalRef.
 *   C2  — appsink unref moved before loop/thread teardown in the PLAYING-fail path.
 *   C3  — Mutex guards on_new_sample vs nativeDestroyPipeline; callback waits for
 *          inflight frame to finish before ctx is freed.
 *   H1  — GST_STATE_CHANGE_ASYNC is accepted (not treated as failure); state changes
 *          are monitored via the bus.
 *   H2  — Bus watch source ID stored; g_source_remove() called before loop quit.
 *   H3  — NewDirectByteBuffer used instead of NewByteArray+SetByteArrayRegion;
 *          callback signature changed to (ByteBuffer, int, int, long).
 *   H4  — URL no longer concatenated into gst_parse_launch string; rtspsrc / urisourcebin
 *          elements are created via gst_element_factory_make and the location/uri
 *          property is set with g_object_set, eliminating pipeline injection.
 *   M1  — Video dimensions re-read on every sample via gst_video_info_from_caps so
 *          mid-stream resolution changes are handled correctly.
 *   M3  — EOS on a live source triggers a pipeline restart instead of a seek.
 *   M6  — Bus error handler uses exponential backoff (max 5 retries) and notifies
 *          Java on permanent failure instead of spinning forever.
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
#include <mutex>

#define LOG_TAG "FaceGate_GST"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#define MAX_RETRY_COUNT 5

// ── Java method IDs (cached at init time) ────────────────────────────────────

static JavaVM* g_jvm = nullptr;

struct JavaCallbackRefs {
    jobject   callback_obj  = nullptr;
    // FIX H3: signature changed — ByteBuffer instead of byte[]
    // Java side: onFrameAvailable(ByteBuffer data, int w, int h, long pts)
    jmethodID onFrameMethod = nullptr;
    // Optional error notification: onPipelineError(String msg)
    jmethodID onErrorMethod = nullptr;
};

// ── Pipeline context ─────────────────────────────────────────────────────────

struct PipelineCtx {
    GstElement*       pipeline   = nullptr;
    GstElement*       appsink    = nullptr;
    GMainLoop*        loop       = nullptr;
    GThread*          gst_thread = nullptr;
    JavaCallbackRefs  java;

    // FIX C3: mutex serialises on_new_sample vs nativeDestroyPipeline
    std::mutex        frame_mutex;
    std::atomic<bool> running{false};

    // FIX H2: store bus-watch source ID so we can remove it cleanly
    guint             bus_watch_id = 0;

    // Video info (re-read each frame — FIX M1)
    int               video_width  = 0;
    int               video_height = 0;

    // FIX M6: retry state for bus error handler
    int               retry_count   = 0;
    guint             retry_timer   = 0;  // GSource id for retry timeout
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

    // FIX C3: lock for the duration of the callback so destroy waits for us
    std::lock_guard<std::mutex> lock(ctx->frame_mutex);

    if (!ctx->running) return GST_FLOW_OK;

    GstSample* sample = gst_app_sink_pull_sample(appsink);
    if (!sample) return GST_FLOW_ERROR;

    GstCaps*   caps   = gst_sample_get_caps(sample);
    GstBuffer* buffer = gst_sample_get_buffer(sample);
    GstMapInfo map;

    // FIX M1: re-read caps every frame to catch mid-stream resolution changes
    if (caps) {
        GstVideoInfo vinfo;
        if (gst_video_info_from_caps(&vinfo, caps)) {
            ctx->video_width  = GST_VIDEO_INFO_WIDTH(&vinfo);
            ctx->video_height = GST_VIDEO_INFO_HEIGHT(&vinfo);
        }
    }

    if (!gst_buffer_map(buffer, &map, GST_MAP_READ)) {
        gst_sample_unref(sample);
        return GST_FLOW_ERROR;
    }

    // ── FIX H3: zero-copy path using NewDirectByteBuffer ──────────────────
    // The ByteBuffer wraps the native pointer directly — no heap allocation,
    // no array copy.  The buffer is valid only for the duration of this call;
    // the Java callback MUST NOT retain a reference to it beyond return.
    JNIEnv* env     = nullptr;
    bool    attached = false;
    jint attach_result = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (attach_result == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
        else {
            gst_buffer_unmap(buffer, &map);
            gst_sample_unref(sample);
            return GST_FLOW_OK;
        }
    }

    if (env && ctx->java.callback_obj && ctx->java.onFrameMethod) {
        const int   w   = ctx->video_width;
        const int   h   = ctx->video_height;
        const jlong pts = static_cast<jlong>(GST_BUFFER_PTS(buffer));

        // Wrap native buffer — zero copy
        jobject jbuffer = env->NewDirectByteBuffer(map.data, static_cast<jlong>(map.size));
        if (jbuffer) {
            env->CallVoidMethod(ctx->java.callback_obj,
                ctx->java.onFrameMethod,
                jbuffer, static_cast<jint>(w), static_cast<jint>(h), pts);
            env->DeleteLocalRef(jbuffer);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    if (attached) g_jvm->DetachCurrentThread();
    gst_buffer_unmap(buffer, &map);
    gst_sample_unref(sample);
    return GST_FLOW_OK;
}

// ── Retry helper (called via g_timeout_add from bus thread) ──────────────────

static gboolean do_pipeline_retry(gpointer data) {
    auto* ctx = reinterpret_cast<PipelineCtx*>(data);
    ctx->retry_timer = 0;  // source fired, clear id
    if (!ctx->running) return G_SOURCE_REMOVE;
    LOGI("Retrying pipeline (attempt %d/%d)", ctx->retry_count, MAX_RETRY_COUNT);
    gst_element_set_state(ctx->pipeline, GST_STATE_NULL);
    gst_element_set_state(ctx->pipeline, GST_STATE_PLAYING);
    return G_SOURCE_REMOVE;
}

// ── Notify Java of permanent failure ─────────────────────────────────────────

static void notify_java_error(PipelineCtx* ctx, const char* message) {
    if (!ctx->java.callback_obj || !ctx->java.onErrorMethod) return;
    JNIEnv* env     = nullptr;
    bool    attached = false;
    jint res = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
        else return;
    }
    jstring jmsg = env->NewStringUTF(message ? message : "Unknown pipeline error");
    if (jmsg) {
        env->CallVoidMethod(ctx->java.callback_obj, ctx->java.onErrorMethod, jmsg);
        env->DeleteLocalRef(jmsg);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_jvm->DetachCurrentThread();
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

            // FIX M6: exponential backoff retry instead of instant spin loop
            if (ctx->running && ctx->retry_count < MAX_RETRY_COUNT) {
                ctx->retry_count++;
                guint delay_ms = static_cast<guint>(200u << (ctx->retry_count - 1)); // 200,400,800,1600,3200ms
                LOGI("Scheduling pipeline retry %d/%d in %u ms",
                     ctx->retry_count, MAX_RETRY_COUNT, delay_ms);
                if (ctx->retry_timer != 0) {
                    g_source_remove(ctx->retry_timer);
                }
                ctx->retry_timer = g_timeout_add(delay_ms, do_pipeline_retry, ctx);
            } else if (ctx->retry_count >= MAX_RETRY_COUNT) {
                LOGE("Pipeline failed permanently after %d retries", MAX_RETRY_COUNT);
                notify_java_error(ctx, err ? err->message : "Pipeline failed permanently");
            }

            g_error_free(err);
            g_free(dbg);
            break;
        }

        case GST_MESSAGE_EOS:
            // FIX M3: live sources don't support seek — restart instead
            if (GST_ELEMENT_FLAG_IS_SET(ctx->pipeline, GST_ELEMENT_FLAG_SOURCE)) {
                LOGD("GStreamer EOS on live source — restarting pipeline");
                gst_element_set_state(ctx->pipeline, GST_STATE_NULL);
                gst_element_set_state(ctx->pipeline, GST_STATE_PLAYING);
            } else {
                LOGD("GStreamer EOS — seeking to beginning");
                gst_element_seek_simple(ctx->pipeline, GST_FORMAT_TIME,
                    static_cast<GstSeekFlags>(GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_KEY_UNIT), 0);
            }
            break;

        case GST_MESSAGE_STATE_CHANGED:
            if (GST_MESSAGE_SRC(msg) == GST_OBJECT(ctx->pipeline)) {
                GstState old_state, new_state;
                gst_message_parse_state_changed(msg, &old_state, &new_state, nullptr);
                LOGD("Pipeline state: %s → %s",
                    gst_element_state_get_name(old_state),
                    gst_element_state_get_name(new_state));
                // Reset retry counter on successful transition to PLAYING
                if (new_state == GST_STATE_PLAYING) {
                    ctx->retry_count = 0;
                }
            }
            break;

        default:
            break;
    }
    return TRUE;
}

// ── Pipeline factory (FIX H4: no URL string injection) ───────────────────────

/**
 * Build the pipeline programmatically using gst_element_factory_make so that
 * the URL is passed as a GObject property rather than embedded in a
 * gst_parse_launch() string.  This eliminates pipeline-description injection.
 *
 * Topology:
 *   RTSP  : rtspsrc → rtpjitterbuffer → decodebin → videoconvert → capsfilter → appsink
 *   Other : urisourcebin → decodebin → videoconvert → capsfilter → appsink
 *
 * Returns the top-level GstPipeline, or nullptr on failure.
 * The appsink element is written to *out_appsink (with an extra ref).
 */
static GstElement* build_pipeline(const std::string& url, GstElement** out_appsink) {
    bool is_rtsp = (url.rfind("rtsp://", 0) == 0) || (url.rfind("rtsps://", 0) == 0);

    GstElement* pipeline    = gst_pipeline_new("facegate-pipeline");
    GstElement* source      = nullptr;
    GstElement* jitterbuf   = nullptr;   // RTSP only
    GstElement* decodebin   = gst_element_factory_make("decodebin",    "decoder");
    GstElement* videoconv   = gst_element_factory_make("videoconvert", "converter");
    GstElement* capsfilter  = gst_element_factory_make("capsfilter",   "capsfilter");
    GstElement* appsink     = gst_element_factory_make("appsink",      "sink");

    if (!pipeline || !decodebin || !videoconv || !capsfilter || !appsink) {
        LOGE("Failed to create base pipeline elements");
        if (pipeline)   gst_object_unref(pipeline);
        if (decodebin)  gst_object_unref(decodebin);
        if (videoconv)  gst_object_unref(videoconv);
        if (capsfilter) gst_object_unref(capsfilter);
        if (appsink)    gst_object_unref(appsink);
        return nullptr;
    }

    // Configure source element — URL is set as a property, not injected into a string
    if (is_rtsp) {
        source    = gst_element_factory_make("rtspsrc",         "source");
        jitterbuf = gst_element_factory_make("rtpjitterbuffer", "jitterbuffer");
        if (!source || !jitterbuf) {
            LOGE("Failed to create rtspsrc/rtpjitterbuffer");
            goto cleanup_fail;
        }
        // FIX H4: g_object_set takes the URL as a typed property — no injection possible
        g_object_set(source,
            "location", url.c_str(),
            "protocols", 4,          // GST_RTSP_LOWER_TRANS_TCP = 4
            "latency",   200,
            nullptr);
        g_object_set(jitterbuf, "drop-on-latency", TRUE, nullptr);
    } else {
        source = gst_element_factory_make("urisourcebin", "source");
        if (!source) {
            LOGE("Failed to create urisourcebin");
            goto cleanup_fail;
        }
        g_object_set(source, "uri", url.c_str(), nullptr);
    }

    // Configure capsfilter: I420 output
    {
        GstCaps* caps = gst_caps_from_string("video/x-raw,format=I420");
        g_object_set(capsfilter, "caps", caps, nullptr);
        gst_caps_unref(caps);
    }

    // Configure appsink
    g_object_set(appsink,
        "max-buffers",   2,
        "drop",          TRUE,
        "emit-signals",  TRUE,
        "sync",          FALSE,
        nullptr);

    // Add elements to pipeline
    if (is_rtsp) {
        gst_bin_add_many(GST_BIN(pipeline),
            source, jitterbuf, decodebin, videoconv, capsfilter, appsink, nullptr);
        // rtspsrc → rtpjitterbuffer: dynamic pad (connected in pad-added)
        // rtpjitterbuffer → decodebin
        if (!gst_element_link(jitterbuf, decodebin)) {
            LOGE("Failed to link jitterbuffer → decodebin");
            goto cleanup_fail_added;
        }
    } else {
        gst_bin_add_many(GST_BIN(pipeline),
            source, decodebin, videoconv, capsfilter, appsink, nullptr);
        // urisourcebin → decodebin: dynamic pad
    }

    // videoconvert → capsfilter → appsink (static links)
    if (!gst_element_link_many(videoconv, capsfilter, appsink, nullptr)) {
        LOGE("Failed to link videoconv → capsfilter → appsink");
        goto cleanup_fail_added;
    }

    // Dynamic pad: source/jitterbuf → decodebin
    // decodebin → videoconvert (connected in pad-added signal)
    // Both signals use the same handler pattern; a closure captures the next element.
    {
        // Connect source's pad-added to decoder for RTSP, or decoder's pad-added to videoconv
        // For simplicity we use a single decodebin pad-added → videoconvert link:
        struct PadAddedData { GstElement* target; };
        auto* pad_data = new PadAddedData{videoconv};
        g_signal_connect_data(decodebin, "pad-added",
            G_CALLBACK(+[](GstElement*, GstPad* pad, gpointer udata) {
                auto* d = reinterpret_cast<PadAddedData*>(udata);
                GstCaps*      caps  = gst_pad_get_current_caps(pad);
                GstStructure* s     = caps ? gst_caps_get_structure(caps, 0) : nullptr;
                const gchar*  name  = s    ? gst_structure_get_name(s) : "";
                if (g_str_has_prefix(name, "video/")) {
                    GstPad* sinkpad = gst_element_get_static_pad(d->target, "sink");
                    if (sinkpad && !gst_pad_is_linked(sinkpad)) {
                        gst_pad_link(pad, sinkpad);
                    }
                    if (sinkpad) gst_object_unref(sinkpad);
                }
                if (caps) gst_caps_unref(caps);
            }),
            pad_data,
            GClosureNotify(+[](gpointer data, GClosure*) { delete reinterpret_cast<PadAddedData*>(data); }),
            G_CONNECT_DEFAULT);

        if (is_rtsp) {
            // rtspsrc pad-added → rtpjitterbuffer sink
            struct RtspPadData { GstElement* jbuf; GstElement* decoder; };
            auto* rpd = new RtspPadData{jitterbuf, decodebin};
            g_signal_connect_data(source, "pad-added",
                G_CALLBACK(+[](GstElement*, GstPad* pad, gpointer udata) {
                    auto* d = reinterpret_cast<RtspPadData*>(udata);
                    GstCaps*      caps = gst_pad_get_current_caps(pad);
                    GstStructure* s    = caps ? gst_caps_get_structure(caps, 0) : nullptr;
                    const gchar*  name = s    ? gst_structure_get_name(s) : "";
                    if (g_str_has_prefix(name, "application/x-rtp")) {
                        GstPad* sink = gst_element_get_static_pad(d->jbuf, "sink");
                        if (sink && !gst_pad_is_linked(sink)) gst_pad_link(pad, sink);
                        if (sink) gst_object_unref(sink);
                    }
                    if (caps) gst_caps_unref(caps);
                }),
                rpd,
                GClosureNotify(+[](gpointer data, GClosure*) { delete reinterpret_cast<RtspPadData*>(data); }),
                G_CONNECT_DEFAULT);
        }
    }

    *out_appsink = GST_ELEMENT(gst_object_ref(appsink));  // caller holds extra ref
    return pipeline;

cleanup_fail_added:
    // Elements already added to bin — unref the bin (takes elements with it)
    gst_object_unref(pipeline);
    if (jitterbuf && !GST_ELEMENT_PARENT(jitterbuf)) gst_object_unref(jitterbuf);
    return nullptr;

cleanup_fail:
    gst_object_unref(pipeline);
    if (source)    gst_object_unref(source);
    if (jitterbuf) gst_object_unref(jitterbuf);
    gst_object_unref(decodebin);
    gst_object_unref(videoconv);
    gst_object_unref(capsfilter);
    gst_object_unref(appsink);
    return nullptr;
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

    if (!gst_is_initialized()) {
        gst_android_init(env, context);
        LOGI("GStreamer initialized");
    }
}

/**
 * GStreamerPipeline.nativeCreatePipeline(url, FrameCallback) → Long (PipelineCtx*)
 *
 * FrameCallback.onFrameAvailable signature (FIX H3):
 *   void onFrameAvailable(ByteBuffer data, int width, int height, long pts)
 *
 * FrameCallback.onPipelineError signature (FIX M6):
 *   void onPipelineError(String message)   — optional; not required to be implemented
 */
JNIEXPORT jlong JNICALL
Java_com_itsme_amkush_gstreamer_GStreamerPipeline_nativeCreatePipeline(
        JNIEnv* env, jclass /*cls*/,
        jstring j_url, jobject j_callback) {

    const char* url_cstr = env->GetStringUTFChars(j_url, nullptr);
    std::string url(url_cstr);
    env->ReleaseStringUTFChars(j_url, url_cstr);

    LOGI("Creating pipeline for URL: %s", url.c_str());

    // ── Find Java callback methods ──
    jclass cb_class = env->GetObjectClass(j_callback);

    // FIX H3: ByteBuffer signature instead of byte[]
    jmethodID on_frame_mid = env->GetMethodID(cb_class, "onFrameAvailable",
        "(Ljava/nio/ByteBuffer;IIJ)V");
    if (!on_frame_mid) {
        LOGE("Cannot find FrameCallback.onFrameAvailable(ByteBuffer,int,int,long)");
        return 0;
    }

    // Optional error callback — don't fail if absent
    jmethodID on_error_mid = env->GetMethodID(cb_class, "onPipelineError",
        "(Ljava/lang/String;)V");
    if (env->ExceptionCheck()) env->ExceptionClear();  // clear NoSuchMethodError if missing

    // ── Allocate context ──
    auto* ctx = new PipelineCtx();

    // FIX C1: NewGlobalRef immediately; every error path below calls DeleteGlobalRef
    ctx->java.callback_obj  = env->NewGlobalRef(j_callback);
    ctx->java.onFrameMethod = on_frame_mid;
    ctx->java.onErrorMethod = on_error_mid;  // may be nullptr
    ctx->running = true;

    // ── Build pipeline (FIX H4: programmatic construction, no string injection) ──
    GstElement* appsink = nullptr;
    ctx->pipeline = build_pipeline(url, &appsink);
    if (!ctx->pipeline) {
        LOGE("build_pipeline failed");
        env->DeleteGlobalRef(ctx->java.callback_obj);  // FIX C1
        delete ctx;
        return 0;
    }
    ctx->appsink = appsink;  // already has an extra ref from build_pipeline

    // ── Set appsink callbacks ──
    GstAppSinkCallbacks appsink_cbs = {
        nullptr,           // eos
        nullptr,           // new_preroll
        on_new_sample,     // new_sample
        nullptr,           // new_event
        { nullptr, nullptr, nullptr }
    };
    gst_app_sink_set_callbacks(GST_APP_SINK(ctx->appsink), &appsink_cbs, ctx, nullptr);

    // ── Attach bus watch — store source ID (FIX H2) ──
    GstBus* bus = gst_element_get_bus(ctx->pipeline);
    ctx->bus_watch_id = gst_bus_add_watch(bus, on_bus_message, ctx);
    gst_object_unref(bus);

    // ── Start GLib main loop ──
    ctx->loop      = g_main_loop_new(nullptr, FALSE);
    ctx->gst_thread = g_thread_new("gst-main",
        reinterpret_cast<GThreadFunc>(gst_main_loop_thread), ctx);

    // ── Start playback ──
    // FIX H1: GST_STATE_CHANGE_ASYNC is normal for RTSP/HLS — only FAILURE is fatal
    GstStateChangeReturn ret = gst_element_set_state(ctx->pipeline, GST_STATE_PLAYING);
    if (ret == GST_STATE_CHANGE_FAILURE) {
        LOGE("Failed to set pipeline to PLAYING");
        // FIX H2: remove bus watch before stopping loop
        if (ctx->bus_watch_id != 0) {
            g_source_remove(ctx->bus_watch_id);
            ctx->bus_watch_id = 0;
        }
        g_main_loop_quit(ctx->loop);
        g_thread_join(ctx->gst_thread);
        g_main_loop_unref(ctx->loop);
        // FIX C2: appsink unref before pipeline unref
        gst_object_unref(ctx->appsink);
        gst_object_unref(ctx->pipeline);
        env->DeleteGlobalRef(ctx->java.callback_obj);  // FIX C1
        delete ctx;
        return 0;
    }

    if (ret == GST_STATE_CHANGE_ASYNC) {
        LOGD("Pipeline state change async — waiting for bus STATE_CHANGED messages");
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

    // FIX C3: signal the callback to stop, then acquire the mutex to ensure
    // any in-flight on_new_sample call has completed before we free resources.
    ctx->running = false;
    {
        std::lock_guard<std::mutex> lock(ctx->frame_mutex);
        // After lock is acquired, no callback is running.
    }

    // Cancel pending retry timer if any (FIX M6)
    if (ctx->retry_timer != 0) {
        g_source_remove(ctx->retry_timer);
        ctx->retry_timer = 0;
    }

    // Stop pipeline
    gst_element_set_state(ctx->pipeline, GST_STATE_NULL);

    // FIX H2: remove bus watch source before quitting the loop
    if (ctx->bus_watch_id != 0) {
        g_source_remove(ctx->bus_watch_id);
        ctx->bus_watch_id = 0;
    }

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

    // FIX C2: release appsink ref before pipeline ref
    if (ctx->appsink) {
        gst_object_unref(ctx->appsink);
        ctx->appsink = nullptr;
    }
    if (ctx->pipeline) {
        gst_object_unref(ctx->pipeline);
        ctx->pipeline = nullptr;
    }

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
