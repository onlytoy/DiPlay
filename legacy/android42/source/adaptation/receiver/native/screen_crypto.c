/* v0.2.94/#296 native screen decryption design, using the already bundled Monocypher. */
#include <jni.h>
#include "vendor/monocypher/monocypher.h"

JNIEXPORT jint JNICALL
Java_local_airuize_receiver_LegacyScreenCrypto_nativeOpen(JNIEnv *env, jobject self,
    jbyteArray key_array, jbyteArray nonce_array, jbyteArray aad_array, jbyteArray data_array, jint length) {
    (void)self;
    if (!key_array || !nonce_array || !aad_array || !data_array) return -2;
    jsize size=(*env)->GetArrayLength(env,data_array);
    jsize aad_size=(*env)->GetArrayLength(env,aad_array);
    if ((*env)->GetArrayLength(env,key_array)!=32 || (*env)->GetArrayLength(env,nonce_array)!=12 ||
        size<16 || size>8*1024*1024 || length!=size-16 || aad_size>4096) return -2;
    unsigned char key[32],nonce[12],aad[4096];
    (*env)->GetByteArrayRegion(env,key_array,0,32,(jbyte *)key);
    (*env)->GetByteArrayRegion(env,nonce_array,0,12,(jbyte *)nonce);
    if (aad_size) (*env)->GetByteArrayRegion(env,aad_array,0,aad_size,(jbyte *)aad);
    if ((*env)->ExceptionCheck(env)) {crypto_wipe(key,sizeof(key));crypto_wipe(nonce,sizeof(nonce));crypto_wipe(aad,sizeof(aad));return -2;}
    jbyte *data=(*env)->GetByteArrayElements(env,data_array,0);
    if (!data) {crypto_wipe(key,sizeof(key));crypto_wipe(nonce,sizeof(nonce));crypto_wipe(aad,sizeof(aad));return -2;}
    crypto_aead_ctx context;
    crypto_aead_init_ietf(&context,key,nonce);
    /* crypto_aead_read verifies the tag before writing plaintext, including for in-place use. */
    int failed=crypto_aead_read(&context,(unsigned char *)data,(unsigned char *)data+length,
                               aad,(size_t)aad_size,(unsigned char *)data,(size_t)length);
    (*env)->ReleaseByteArrayElements(env,data_array,data,failed ? JNI_ABORT : 0);
    crypto_wipe(&context,sizeof(context));crypto_wipe(key,sizeof(key));
    crypto_wipe(nonce,sizeof(nonce));crypto_wipe(aad,sizeof(aad));
    return failed ? -1 : length;
}
