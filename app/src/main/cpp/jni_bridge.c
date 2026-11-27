/*
 * Cast Craft - JNI bridge between the UxPlay AirPlay server library and the
 * Android app (com.castcraft.airplay.AirPlayServer).
 *
 * Only the network/protocol side (RTSP, pairing, FairPlay, RTP, decryption)
 * runs in native code. Decoding and rendering are done in Java with
 * MediaCodec / AudioTrack.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef __ANDROID__
#include <android/log.h>
#define ALOG(prio, ...) __android_log_print(prio, "CastCraftNative", __VA_ARGS__)
#define PRIO_D ANDROID_LOG_DEBUG
#define PRIO_I ANDROID_LOG_INFO
#define PRIO_W ANDROID_LOG_WARN
#define PRIO_E ANDROID_LOG_ERROR
#else
#define ALOG(prio, ...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define PRIO_D 3
#define PRIO_I 4
#define PRIO_W 5
#define PRIO_E 6
#endif

#include "uxplay/lib/raop.h"
#include "uxplay/lib/dnssd.h"
#include "uxplay/lib/logger.h"
#include "uxplay/lib/stream.h"

#include "android_nsd.h"

static JavaVM *g_vm = NULL;
static jobject g_server = NULL; /* global ref to the AirPlayServer instance */
static pthread_key_t g_env_key;
static pthread_once_t g_key_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static jmethodID m_registerService;
static jmethodID m_unregisterService;
static jmethodID m_onVideoData;
static jmethodID m_onVideoSize;
static jmethodID m_onAudioFormat;
static jmethodID m_onAudioData;
static jmethodID m_onAudioVolume;
static jmethodID m_onAudioFlush;
static jmethodID m_onVideoFlush;
static jmethodID m_onConnectionOpened;
static jmethodID m_onConnectionClosed;
static jmethodID m_onConnectionReset;
static jmethodID m_onVideoReset;
static jmethodID m_onClientRequest;
static jmethodID m_onHlsPlay;
static jmethodID m_onHlsSeek;
static jmethodID m_onHlsRate;
static jmethodID m_onHlsStop;
static jmethodID m_getHlsPlaybackInfo;
static jmethodID m_onHlsPlaylistRemove;
static char g_lang_system[32] = "en";

static raop_t *g_raop = NULL;
static dnssd_t *g_dnssd = NULL;
static int g_open_connections = 0;

/* ------------------------------------------------------------------ JNIEnv */

static void detach_thread(void *unused)
{
    (void) unused;
    if (g_vm) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

static void make_key(void)
{
    pthread_key_create(&g_env_key, detach_thread);
}

static JNIEnv *get_env(void)
{
    JNIEnv *env = NULL;
    if (!g_vm) return NULL;
    jint res = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (res == JNI_OK) return env;
    if (res == JNI_EDETACHED) {
#ifdef __ANDROID__
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
#else
        if ((*g_vm)->AttachCurrentThread(g_vm, (void **) &env, NULL) != JNI_OK) return NULL;
#endif
        pthread_once(&g_key_once, make_key);
        pthread_setspecific(g_env_key, (void *) 1);
        return env;
    }
    return NULL;
}

static void check_exception(JNIEnv *env)
{
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

/* ------------------------------------------------------------ NSD bridge */

int android_nsd_register(const char *type, const char *name, unsigned short port,
                         const char **keys, const char **values, int count)
{
    JNIEnv *env = get_env();
    if (!env || !g_server) return -1;
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray jkeys = (*env)->NewObjectArray(env, count, string_class, NULL);
    jobjectArray jvalues = (*env)->NewObjectArray(env, count, string_class, NULL);
    for (int i = 0; i < count; i++) {
        jstring k = (*env)->NewStringUTF(env, keys[i]);
        jstring v = (*env)->NewStringUTF(env, values[i] ? values[i] : "");
        (*env)->SetObjectArrayElement(env, jkeys, i, k);
        (*env)->SetObjectArrayElement(env, jvalues, i, v);
        (*env)->DeleteLocalRef(env, k);
        (*env)->DeleteLocalRef(env, v);
    }
    jstring jtype = (*env)->NewStringUTF(env, type);
    jstring jname = (*env)->NewStringUTF(env, name);
    jboolean ok = (*env)->CallBooleanMethod(env, g_server, m_registerService, jtype, jname,
                                            (jint) port, jkeys, jvalues);
    check_exception(env);
    (*env)->DeleteLocalRef(env, jtype);
    (*env)->DeleteLocalRef(env, jname);
    (*env)->DeleteLocalRef(env, jkeys);
    (*env)->DeleteLocalRef(env, jvalues);
    (*env)->DeleteLocalRef(env, string_class);
    return ok ? 0 : -1;
}

void android_nsd_unregister(const char *type)
{
    JNIEnv *env = get_env();
    if (!env || !g_server) return;
    jstring jtype = (*env)->NewStringUTF(env, type);
    (*env)->CallVoidMethod(env, g_server, m_unregisterService, jtype);
    check_exception(env);
    (*env)->DeleteLocalRef(env, jtype);
}

/* ------------------------------------------------------ helper callers */

static void call_void(jmethodID m)
{
    JNIEnv *env = get_env();
    if (!env || !g_server) return;
    (*env)->CallVoidMethod(env, g_server, m);
    check_exception(env);
}

static void call_void_int(jmethodID m, int v)
{
    JNIEnv *env = get_env();
    if (!env || !g_server) return;
    (*env)->CallVoidMethod(env, g_server, m, (jint) v);
    check_exception(env);
}

static void call_bytes(jmethodID m, const unsigned char *data, int len, uint64_t ts)
{
    JNIEnv *env = get_env();
    if (!env || !g_server || len <= 0) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) {
        check_exception(env);
        return;
    }
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) data);
    (*env)->CallVoidMethod(env, g_server, m, arr, (jlong) ts);
    check_exception(env);
    (*env)->DeleteLocalRef(env, arr);
}

/* --------------------------------------------------------- raop callbacks */

static void cb_conn_init(void *cls)
{
    (void) cls;
    g_open_connections++;
    ALOG(PRIO_D, "open connections: %d", g_open_connections);
    call_void(m_onConnectionOpened);
}

static void cb_conn_destroy(void *cls)
{
    (void) cls;
    g_open_connections--;
    ALOG(PRIO_D, "open connections: %d", g_open_connections);
    call_void_int(m_onConnectionClosed, g_open_connections);
}

static void cb_conn_reset(void *cls, int reason)
{
    (void) cls;
    ALOG(PRIO_I, "connection reset, reason %d", reason);
    call_void_int(m_onConnectionReset, reason);
}

static void cb_conn_feedback(void *cls)
{
    (void) cls;
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data)
{
    (void) cls;
    (void) ntp;
    if (data->is_h265) return; /* only H.264 is advertised */
    call_bytes(m_onVideoData, data->data, data->data_len, data->ntp_time_remote);
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data)
{
    (void) cls;
    (void) ntp;
    call_bytes(m_onAudioData, data->data, data->data_len, data->ntp_time_remote);
}

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen,
                                bool *isMedia, uint64_t *audioFormat)
{
    (void) cls;
    ALOG(PRIO_I, "audio format ct=%d spf=%d usingScreen=%d isMedia=%d audioFormat=0x%llx", *ct, *spf,
         *usingScreen, *isMedia, (unsigned long long) *audioFormat);
    JNIEnv *env = get_env();
    if (!env || !g_server) return;
    (*env)->CallVoidMethod(env, g_server, m_onAudioFormat, (jint) *ct, (jint) *spf);
    check_exception(env);
}

static void cb_audio_flush(void *cls)
{
    (void) cls;
    call_void(m_onAudioFlush);
}

static void cb_video_flush(void *cls)
{
    (void) cls;
    call_void(m_onVideoFlush);
}

static void cb_video_pause(void *cls)
{
    (void) cls;
}

static void cb_video_resume(void *cls)
{
    (void) cls;
}

static double cb_audio_set_client_volume(void *cls)
{
    (void) cls;
    return 0.0;
}

static void cb_audio_set_volume(void *cls, float volume)
{
    (void) cls;
    JNIEnv *env = get_env();
    if (!env || !g_server) return;
    (*env)->CallVoidMethod(env, g_server, m_onAudioVolume, (jfloat) volume);
    check_exception(env);
}

static void cb_audio_set_metadata(void *cls, const void *buffer, int buflen)
{
    (void) cls; (void) buffer; (void) buflen;
}

static void cb_audio_set_coverart(void *cls, const void *buffer, int buflen)
{
    (void) cls; (void) buffer; (void) buflen;
}

static void cb_audio_stop_coverart_rendering(void *cls)
{
    (void) cls;
}

static void cb_audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end)
{
    (void) cls; (void) start; (void) curr; (void) end;
}

static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit)
{
    (void) cls;
    ALOG(PRIO_I, "connection request from %s (%s), deviceID %s", name ? name : "?", model ? model : "?",
         deviceid ? deviceid : "?");
    *admit = true;
    JNIEnv *env = get_env();
    if (!env || !g_server) return;
    jstring jname = (*env)->NewStringUTF(env, name ? name : "");
    jstring jmodel = (*env)->NewStringUTF(env, model ? model : "");
    (*env)->CallVoidMethod(env, g_server, m_onClientRequest, jname, jmodel);
    check_exception(env);
    (*env)->DeleteLocalRef(env, jname);
    (*env)->DeleteLocalRef(env, jmodel);
}

static void cb_display_pin(void *cls, char *pin)
{
    (void) cls;
    ALOG(PRIO_I, "pin: %s", pin);
}

static void cb_register_client(void *cls, const char *device_id, const char *pk_str, const char *name)
{
    (void) cls; (void) device_id; (void) pk_str; (void) name;
}

static bool cb_check_register(void *cls, const char *pk_str)
{
    (void) cls; (void) pk_str;
    return true;
}

static const char *cb_passwd(void *cls, int *len)
{
    (void) cls;
    *len = 0; /* no access control */
    return NULL;
}

static void cb_export_dacp(void *cls, const char *active_remote, const char *dacp_id)
{
    (void) cls; (void) active_remote; (void) dacp_id;
}

static void cb_video_reset(void *cls, reset_type_t type)
{
    (void) cls;
    ALOG(PRIO_I, "video reset, type %d", (int) type);
    /* same library-side cleanup as UxPlay's video_reset() */
    if (g_raop) {
        if (type == RESET_TYPE_NOHOLD) {
            raop_destroy_airplay_video(g_raop, -1);
        } else if (type == RESET_TYPE_HLS_SHUTDOWN) {
            raop_destroy_airplay_video(g_raop, -1);
            raop_remove_hls_connections(g_raop);
        }
    }
    call_void_int(m_onVideoReset, (int) type);
}

/* ------------------------------------------- AirPlay video (HLS) callbacks */

static void cb_on_video_play(void *cls, const char *location, const float start_position)
{
    (void) cls;
    ALOG(PRIO_I, "video play %s from %f s", location, start_position);
    JNIEnv *env = get_env();
    if (env && g_server) {
        jstring jurl = (*env)->NewStringUTF(env, location);
        (*env)->CallVoidMethod(env, g_server, m_onHlsPlay, jurl, (jfloat) start_position);
        check_exception(env);
        (*env)->DeleteLocalRef(env, jurl);
    }
    cb_video_reset(cls, RESET_TYPE_ON_VIDEO_PLAY);
}

static void cb_on_video_scrub(void *cls, const float position)
{
    (void) cls;
    JNIEnv *env = get_env();
    if (!env || !g_server) return;
    (*env)->CallVoidMethod(env, g_server, m_onHlsSeek, (jfloat) position);
    check_exception(env);
}

static void cb_on_video_rate(void *cls, const float rate)
{
    (void) cls;
    JNIEnv *env = get_env();
    if (!env || !g_server) return;
    (*env)->CallVoidMethod(env, g_server, m_onHlsRate, (jfloat) rate);
    check_exception(env);
}

static void cb_on_video_stop(void *cls)
{
    (void) cls;
    call_void(m_onHlsStop);
}

static void cb_on_video_acquire_playback_info(void *cls, playback_info_t *info)
{
    (void) cls;
    info->duration = 0.0;
    info->position = -1.0;
    info->seek_start = 0.0;
    info->seek_duration = 0.0;
    info->rate = 0.0f;
    info->playback_buffer_empty = false;
    info->playback_buffer_full = true;
    info->ready_to_play = true;
    info->playback_likely_to_keep_up = true;
    JNIEnv *env = get_env();
    if (!env || !g_server) return;
    jdoubleArray arr = (jdoubleArray) (*env)->CallObjectMethod(env, g_server, m_getHlsPlaybackInfo);
    check_exception(env);
    if (!arr) return;
    jdouble v[6] = {0};
    if ((*env)->GetArrayLength(env, arr) >= 6) {
        (*env)->GetDoubleArrayRegion(env, arr, 0, 6, v);
        info->duration = v[0];
        info->position = v[1];
        info->seek_start = v[2];
        info->seek_duration = v[3];
        info->rate = (float) v[4];
        if (v[5] != 0.0) {
            /* finished: tells the library to end the session (as UxPlay does) */
            ALOG(PRIO_I, "video has finished at %f", v[1]);
            info->position = -1.0;
            info->duration = -1.0;
            call_void(m_onHlsStop);
        }
    }
    (*env)->DeleteLocalRef(env, arr);
}

static float cb_on_video_playlist_remove(void *cls)
{
    (void) cls;
    JNIEnv *env = get_env();
    if (!env || !g_server) return 0.0f;
    jfloat pos = (*env)->CallFloatMethod(env, g_server, m_onHlsPlaylistRemove);
    check_exception(env);
    return (float) pos;
}

static int cb_video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short width_source,
                                       unsigned short height_source, unsigned short x_offset,
                                       unsigned short y_offset, unsigned short width, unsigned short height)
{
    (void) cls; (void) x_offset; (void) y_offset;
    ALOG(PRIO_I, "video codec %d, source %ux%u, size %ux%u", (int) codec, width_source, height_source,
         width, height);
    if (codec == VIDEO_CODEC_H265) {
        return -1;
    }
    JNIEnv *env = get_env();
    if (env && g_server) {
        (*env)->CallVoidMethod(env, g_server, m_onVideoSize, (jint) width, (jint) height);
        check_exception(env);
    }
    return 0;
}

static void cb_get_custom_profile(void *cls, const char **custom_profile)
{
    (void) cls;
    *custom_profile = NULL;
}

static void cb_log(void *cls, int level, const char *msg)
{
    (void) cls;
    int prio;
    switch (level) {
    case LOGGER_EMERG:
    case LOGGER_ALERT:
    case LOGGER_CRIT:
    case LOGGER_ERR:
        prio = PRIO_E;
        break;
    case LOGGER_WARNING:
        prio = PRIO_W;
        break;
    case LOGGER_NOTICE:
    case LOGGER_INFO:
        prio = PRIO_I;
        break;
    default:
        prio = PRIO_D;
        break;
    }
    ALOG(prio, "%s", msg);
}

/* ------------------------------------------------------------- lifecycle */

static int parse_hw_addr(const char *str, char *out, int max)
{
    int n = 0;
    unsigned int byte;
    const char *p = str;
    while (*p && n < max) {
        if (sscanf(p, "%2x", &byte) != 1) return -1;
        out[n++] = (char) byte;
        p += 2;
        if (*p == ':' || *p == '-') p++;
    }
    return n;
}

static void stop_locked(void)
{
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
    }
    if (g_raop) {
        raop_destroy(g_raop);
        g_raop = NULL;
    }
    if (g_dnssd) {
        dnssd_destroy(g_dnssd);
        g_dnssd = NULL;
    }
    g_open_connections = 0;
}

static void set_features(dnssd_t *dnssd)
{
    /* same defaults as UxPlay (uxplay.cpp start_dnssd), H.264 mirroring only */
    /* bits 0 and 4: AirPlay video + HLS (UxPlay -hls), bit 42 off: no H.265 */
    static const int on[] = {0, 1, 2, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 25, 28, 30};
    static const int off[] = {3, 8, 23, 24, 26, 27, 29, 31, 42};
    for (size_t i = 0; i < sizeof(on) / sizeof(on[0]); i++) dnssd_set_airplay_features(dnssd, on[i], 1);
    for (size_t i = 0; i < sizeof(off) / sizeof(off[0]); i++) dnssd_set_airplay_features(dnssd, off[i], 0);
}

static jboolean start_server(JNIEnv *env, jobject thiz, jstring jname, jstring jmac, jstring jkeyfile,
                             jint width, jint height, jint fps, jstring jlang)
{
    pthread_mutex_lock(&g_lock);
    stop_locked();
    if (g_server) {
        (*env)->DeleteGlobalRef(env, g_server);
        g_server = NULL;
    }
    g_server = (*env)->NewGlobalRef(env, thiz);

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *mac = (*env)->GetStringUTFChars(env, jmac, NULL);
    const char *keyfile = (*env)->GetStringUTFChars(env, jkeyfile, NULL);
    jboolean result = JNI_FALSE;
    if (jlang) {
        const char *lang = (*env)->GetStringUTFChars(env, jlang, NULL);
        if (lang && *lang) snprintf(g_lang_system, sizeof(g_lang_system), "%s", lang);
        (*env)->ReleaseStringUTFChars(env, jlang, lang);
    }

    char hw_addr[6];
    int error = 0;
    if (parse_hw_addr(mac, hw_addr, 6) != 6) {
        ALOG(PRIO_E, "bad MAC address %s", mac);
        goto out;
    }

    g_dnssd = dnssd_init(name, (int) strlen(name), hw_addr, 6, 0, &error);
    if (!g_dnssd || error) {
        ALOG(PRIO_E, "dnssd_init failed: %d", error);
        g_dnssd = NULL;
        goto out;
    }
    set_features(g_dnssd);

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_reset = cb_conn_reset;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
    cbs.get_custom_profile = cb_get_custom_profile;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;

    g_raop = raop_init(&cbs);
    if (!g_raop) {
        ALOG(PRIO_E, "raop_init failed");
        goto fail;
    }
    raop_set_log_callback(g_raop, cb_log, NULL);
    raop_set_log_level(g_raop, LOGGER_INFO);
    /* nohold = 1: a new client can take over from the current one */
    if (raop_init2(g_raop, 1, mac, keyfile)) {
        ALOG(PRIO_E, "raop_init2 failed");
        free(g_raop); /* same as UxPlay: raop_init2 failure leaves a partially set up raop */
        g_raop = NULL;
        goto fail;
    }
    if (width > 0) raop_set_plist(g_raop, "width", width);
    if (height > 0) raop_set_plist(g_raop, "height", height);
    if (fps > 0) {
        raop_set_plist(g_raop, "refreshRate", fps);
        raop_set_plist(g_raop, "maxFPS", fps);
    }
    raop_set_plist(g_raop, "hls", 1);

    unsigned short tcp[3] = {0, 0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);

    unsigned short port = raop_get_port(g_raop);
    if (raop_start_httpd(g_raop, &port) < 0) {
        ALOG(PRIO_E, "raop_start_httpd failed");
        goto fail;
    }
    raop_set_port(g_raop, port);
    raop_set_dnssd(g_raop, g_dnssd);
    raop_set_lang(g_raop, "", "", g_lang_system); /* stores the pointer: g_lang_system is static */
    ALOG(PRIO_I, "AirPlay server listening on port %u", port);

    if (dnssd_register_raop(g_dnssd, port) || dnssd_register_airplay(g_dnssd, port)) {
        ALOG(PRIO_E, "service registration failed");
        goto fail;
    }
    result = JNI_TRUE;
    goto out;

fail:
    stop_locked();
out:
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jmac, mac);
    (*env)->ReleaseStringUTFChars(env, jkeyfile, keyfile);
    pthread_mutex_unlock(&g_lock);
    return result;
}

static void stop_server(JNIEnv *env, jobject thiz)
{
    (void) thiz;
    pthread_mutex_lock(&g_lock);
    stop_locked();
    if (g_server) {
        (*env)->DeleteGlobalRef(env, g_server);
        g_server = NULL;
    }
    pthread_mutex_unlock(&g_lock);
}

static void reset_connections(JNIEnv *env, jobject thiz)
{
    (void) env; (void) thiz;
    pthread_mutex_lock(&g_lock);
    if (g_raop) {
        raop_remove_known_connections(g_raop);
    }
    pthread_mutex_unlock(&g_lock);
}

static const JNINativeMethod methods[] = {
    {"nativeStart", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIILjava/lang/String;)Z",
     (void *) start_server},
    {"nativeStop", "()V", (void *) stop_server},
    {"nativeResetConnections", "()V", (void *) reset_connections},
};

#define SERVER_CLASS "com/castcraft/airplay/AirPlayServer"

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void) reserved;
    JNIEnv *env;
    g_vm = vm;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, SERVER_CLASS);
    if (!cls) return JNI_ERR;
    if ((*env)->RegisterNatives(env, cls, methods, sizeof(methods) / sizeof(methods[0])) != 0) return JNI_ERR;

#define GET(var, name, sig) \
    var = (*env)->GetMethodID(env, cls, name, sig); \
    if (!var) return JNI_ERR;
    GET(m_registerService, "registerService",
        "(Ljava/lang/String;Ljava/lang/String;I[Ljava/lang/String;[Ljava/lang/String;)Z");
    GET(m_unregisterService, "unregisterService", "(Ljava/lang/String;)V");
    GET(m_onVideoData, "onVideoData", "([BJ)V");
    GET(m_onVideoSize, "onVideoSize", "(II)V");
    GET(m_onAudioFormat, "onAudioFormat", "(II)V");
    GET(m_onAudioData, "onAudioData", "([BJ)V");
    GET(m_onAudioVolume, "onAudioVolume", "(F)V");
    GET(m_onAudioFlush, "onAudioFlush", "()V");
    GET(m_onVideoFlush, "onVideoFlush", "()V");
    GET(m_onConnectionOpened, "onConnectionOpened", "()V");
    GET(m_onConnectionClosed, "onConnectionClosed", "(I)V");
    GET(m_onConnectionReset, "onConnectionReset", "(I)V");
    GET(m_onVideoReset, "onVideoReset", "(I)V");
    GET(m_onClientRequest, "onClientRequest", "(Ljava/lang/String;Ljava/lang/String;)V");
    GET(m_onHlsPlay, "onHlsPlay", "(Ljava/lang/String;F)V");
    GET(m_onHlsSeek, "onHlsSeek", "(F)V");
    GET(m_onHlsRate, "onHlsRate", "(F)V");
    GET(m_onHlsStop, "onHlsStop", "()V");
    GET(m_getHlsPlaybackInfo, "getHlsPlaybackInfo", "()[D");
    GET(m_onHlsPlaylistRemove, "onHlsPlaylistRemove", "()F");
#undef GET
    (*env)->DeleteLocalRef(env, cls);
    return JNI_VERSION_1_6;
}
