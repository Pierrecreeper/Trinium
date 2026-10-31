/* Negative-test C fixture only. NEVER included in the mod or presented as Rust. */
#include <jni.h>
#include <stdlib.h>
JNIEXPORT jlong JNICALL Java_fr_pierrecreeper_trinium_NativeBridge_apiVersion0(JNIEnv *env,jclass cls) {
#ifdef ABORT_TEST
abort();
#endif
#ifdef BAD_ABI
return 99;
#else
return 3;
#endif
}
JNIEXPORT jlong JNICALL Java_fr_pierrecreeper_trinium_NativeBridge_resolve0(JNIEnv *env,jclass cls,jobject obj) { return 0; }
