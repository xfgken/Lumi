// H2A JNI glue —— 桥接 Java(Kotlin) 与 Go 核心
//
// 本文件经 main.go 的 cgo preamble `#include "jni_glue.c"` 编译进 libh2a.so。
// 实现：
//   1. JNI_OnLoad：缓存 JavaVM 与 H2aNative 类引用（供 protectFd 回调）
//   2. Java_ 命名约定的 native 方法（与 com.xfgken.h2a.core.H2aNative 一一对应）
//   3. h2aProtectFd()：Go 侧 dial 前回调 Java 执行 VpnService.protect()
//
#include <jni.h>
#include <stdlib.h>
#include <string.h>

// ---- Go 侧导出函数（由 cgo //export 生成，原型与 _cgo_export.h 一致）----
extern long long GoConnect(char *json, int len);
extern void GoStop(long long sid);
extern int GoSetTunFd(long long sid, int fd);
extern char *GoGetStats(long long sid);
extern char *GoGetLogs(void);
extern void GoClearLogs(void);
extern void GoSetProxyMode(int mode);
extern void GoSetCnList(char *text, int len);

// ---- 全局 JVM 缓存 ----
static JavaVM *g_jvm = NULL;
static jclass g_h2aClass = NULL;
static jmethodID g_protectFdMethod = NULL;

// ---------------------------------------------------------------------------
// JNI_OnLoad：缓存全局引用
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass local = (*env)->FindClass(env, "com/xfgken/h2a/core/H2aNative");
    if (local == NULL) {
        return JNI_ERR;
    }
    g_h2aClass = (jclass)(*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (g_h2aClass == NULL) {
        return JNI_ERR;
    }
    g_protectFdMethod = (*env)->GetStaticMethodID(env, g_h2aClass, "protectFd", "(I)V");
    if (g_protectFdMethod == NULL) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

// ---------------------------------------------------------------------------
// protectFd 回调：Go 核心在建立底层连接前调用，把 socket fd 加入 VPN 白名单，
// 防止核心流量进入自身 TUN 造成环路。
// ---------------------------------------------------------------------------
void h2aProtectFd(int fd) {
    if (g_jvm == NULL || g_h2aClass == NULL || g_protectFdMethod == NULL) {
        return;
    }
    JNIEnv *env = NULL;
    jint attached = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);
    if (attached == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) {
            return;
        }
    } else if (attached != JNI_OK) {
        return;
    }
    (*env)->CallStaticVoidMethod(env, g_h2aClass, g_protectFdMethod, (jint)fd);
    if (attached == JNI_EDETACHED) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

// ---------------------------------------------------------------------------
// 辅助：jstring <-> char*
// ---------------------------------------------------------------------------
static char *jstringToCStr(JNIEnv *env, jstring js) {
    if (js == NULL) return NULL;
    const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
    if (utf == NULL) return NULL;
    char *dup = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, js, utf);
    return dup;
}

static jstring cstrToJString(JNIEnv *env, char *cstr) {
    if (cstr == NULL) return NULL;
    jstring js = (*env)->NewStringUTF(env, cstr);
    free(cstr);
    return js;
}

// ---------------------------------------------------------------------------
// native 方法（命名约定绑定，与 Kotlin 声明一一对应）
// ---------------------------------------------------------------------------

JNIEXPORT jlong JNICALL Java_com_xfgken_h2a_core_H2aNative_nativeConnect(
    JNIEnv *env, jobject thiz, jstring nodeJson) {
    (void)thiz;
    char *json = jstringToCStr(env, nodeJson);
    if (json == NULL) return -2;
    jlong sid = (jlong)GoConnect(json, (int)strlen(json));
    free(json);
    return sid;
}

JNIEXPORT void JNICALL Java_com_xfgken_h2a_core_H2aNative_nativeStop(
    JNIEnv *env, jobject thiz, jlong sid) {
    (void)env; (void)thiz;
    GoStop((long long)sid);
}

JNIEXPORT jint JNICALL Java_com_xfgken_h2a_core_H2aNative_nativeSetTunFd(
    JNIEnv *env, jobject thiz, jlong sid, jint fd) {
    (void)env; (void)thiz;
    return (jint)GoSetTunFd((long long)sid, (int)fd);
}

JNIEXPORT jstring JNICALL Java_com_xfgken_h2a_core_H2aNative_nativeGetStats(
    JNIEnv *env, jobject thiz, jlong sid) {
    (void)thiz;
    char *out = GoGetStats((long long)sid);
    return cstrToJString(env, out);
}

JNIEXPORT jstring JNICALL Java_com_xfgken_h2a_core_H2aNative_nativeGetLogs(
    JNIEnv *env, jobject thiz) {
    (void)thiz;
    char *out = GoGetLogs();
    return cstrToJString(env, out);
}

JNIEXPORT void JNICALL Java_com_xfgken_h2a_core_H2aNative_nativeClearLogs(
    JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    GoClearLogs();
}

JNIEXPORT void JNICALL Java_com_xfgken_h2a_core_H2aNative_nativeSetProxyMode(
    JNIEnv *env, jobject thiz, jint mode) {
    (void)env; (void)thiz;
    GoSetProxyMode((int)mode);
}

JNIEXPORT void JNICALL Java_com_xfgken_h2a_core_H2aNative_nativeSetCnList(
    JNIEnv *env, jobject thiz, jstring list) {
    (void)thiz;
    char *text = jstringToCStr(env, list);
    if (text == NULL) return;
    GoSetCnList(text, (int)strlen(text));
    free(text);
}