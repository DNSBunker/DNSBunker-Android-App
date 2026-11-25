//go:build android

#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include "_cgo_export.h"

JNIEXPORT jlong JNICALL Java_org_dnsbunker_android_core_NativeQuic_nativeCreate(JNIEnv *e,jclass cls,jint fd,jstring ip,jstring peer,jstring roots){
 const char *a=(*e)->GetStringUTFChars(e,ip,0),*b=(*e)->GetStringUTFChars(e,peer,0),*c=(*e)->GetStringUTFChars(e,roots,0);
 if(!a||!b||!c){if(a)(*e)->ReleaseStringUTFChars(e,ip,a);if(b)(*e)->ReleaseStringUTFChars(e,peer,b);if(c)(*e)->ReleaseStringUTFChars(e,roots,c);return 0;}
 jlong result=dnsbunker_create(fd,(char*)a,(char*)b,(char*)c);
 (*e)->ReleaseStringUTFChars(e,ip,a);(*e)->ReleaseStringUTFChars(e,peer,b);(*e)->ReleaseStringUTFChars(e,roots,c);return result;
}
static jbyteArray query_result(JNIEnv *e,jlong handle,jint kind,jbyteArray query,jint timeout,int timed){
 jsize n=(*e)->GetArrayLength(e,query);if(n<12||n>65535)return NULL;
 jbyte *q=(*e)->GetByteArrayElements(e,query,0);if(!q)return NULL;
 unsigned char *out=malloc(65552);if(!out){(*e)->ReleaseByteArrayElements(e,query,q,JNI_ABORT);return NULL;}
 int size=dnsbunker_query(handle,kind,q,n,out,timeout);(*e)->ReleaseByteArrayElements(e,query,q,JNI_ABORT);
 jbyteArray result=NULL;
 if(size>=29&&size<=65552){int offset=timed?0:17;size-=offset;result=(*e)->NewByteArray(e,size);if(result)(*e)->SetByteArrayRegion(e,result,0,size,(jbyte*)(out+offset));}
 else {const char *message=size==-5?"QUIC certificate time invalid":size==-3?"QUIC certificate verification failed":size==-2?"QUIC timeout":"QUIC connection or response failed";
 jclass error=(*e)->FindClass(e,"java/io/IOException");if(error)(*e)->ThrowNew(e,error,message);}
 free(out);return result;
}
JNIEXPORT jbyteArray JNICALL Java_org_dnsbunker_android_core_NativeQuic_nativeQuery(JNIEnv *e,jclass cls,jlong h,jint k,jbyteArray q,jint t){return query_result(e,h,k,q,t,0);}
JNIEXPORT jbyteArray JNICALL Java_org_dnsbunker_android_core_NativeQuic_nativeQueryTimed(JNIEnv *e,jclass cls,jlong h,jint k,jbyteArray q,jint t){return query_result(e,h,k,q,t,1);}
JNIEXPORT void JNICALL Java_org_dnsbunker_android_core_NativeQuic_nativeClose(JNIEnv *e,jclass cls,jlong handle){dnsbunker_close(handle);}

#include <pthread.h>
static JavaVM* bridge_vm;
static jclass full_class;
static jmethodID protect_method, emit_method, dns_method;
static pthread_mutex_t bridge_lock=PTHREAD_MUTEX_INITIALIZER;
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm,void* reserved){bridge_vm=vm;return JNI_VERSION_1_6;}
static pthread_key_t bridge_key;
static pthread_once_t bridge_once=PTHREAD_ONCE_INIT;
static void bridge_detach(void* value){if(value&&bridge_vm)(*bridge_vm)->DetachCurrentThread(bridge_vm);}
static void bridge_key_init(void){pthread_key_create(&bridge_key,bridge_detach);}
static JNIEnv* bridge_env(void){
 JNIEnv* env=NULL;if(!bridge_vm)return NULL;
 jint state=(*bridge_vm)->GetEnv(bridge_vm,(void**)&env,JNI_VERSION_1_6);
 if(state==JNI_OK)return env;
 if(state!=JNI_EDETACHED)return NULL;
 pthread_once(&bridge_once,bridge_key_init);
 if((*bridge_vm)->AttachCurrentThreadAsDaemon(bridge_vm,&env,NULL)!=JNI_OK)return NULL;
 pthread_setspecific(bridge_key,(void*)1);
 return env;
}
int bridge_protect(long long token,int fd){JNIEnv* e=bridge_env();if(!e||!full_class)return 0;
 jboolean ok=(*e)->CallStaticBooleanMethod(e,full_class,protect_method,(jlong)token,(jint)fd);
 if((*e)->ExceptionCheck(e)){(*e)->ExceptionClear(e);ok=0;}return ok?1:0;
}
int bridge_emit(long long token,void* data,int n){JNIEnv* e=bridge_env();if(!e||!full_class)return 0;
 jbyteArray b=(*e)->NewByteArray(e,n);jboolean ok=0;if(b){(*e)->SetByteArrayRegion(e,b,0,n,data);ok=(*e)->CallStaticBooleanMethod(e,full_class,emit_method,(jlong)token,b);(*e)->DeleteLocalRef(e,b);}
 if((*e)->ExceptionCheck(e)){(*e)->ExceptionClear(e);ok=0;}return ok?1:0;
}
int bridge_dns(long long token,void* data,int n,int udp,int port,void* output){JNIEnv* e=bridge_env();if(!e||!full_class)return 0;
 int count=0;jbyteArray q=(*e)->NewByteArray(e,n);if(q){(*e)->SetByteArrayRegion(e,q,0,n,data);
 jbyteArray answer=(*e)->CallStaticObjectMethod(e,full_class,dns_method,(jlong)token,q,(jboolean)udp,(jint)port);(*e)->DeleteLocalRef(e,q);
 if(!(*e)->ExceptionCheck(e)&&answer){count=(*e)->GetArrayLength(e,answer);if(count>=12&&count<=65535)(*e)->GetByteArrayRegion(e,answer,0,count,output);else count=0;(*e)->DeleteLocalRef(e,answer);}}
 if((*e)->ExceptionCheck(e)){(*e)->ExceptionClear(e);count=0;}return count;
}
JNIEXPORT jboolean JNICALL Java_org_dnsbunker_android_core_NativeFullTunnel_nativeStart(JNIEnv* e,jclass cls,jlong token,jint mtu){
 pthread_mutex_lock(&bridge_lock);if(!full_class){full_class=(*e)->NewGlobalRef(e,cls);protect_method=(*e)->GetStaticMethodID(e,cls,"protectSocket","(JI)Z");emit_method=(*e)->GetStaticMethodID(e,cls,"emitPacket","(J[B)Z");dns_method=(*e)->GetStaticMethodID(e,cls,"resolveDns","(J[BZI)[B");}pthread_mutex_unlock(&bridge_lock);
 if(!full_class||!protect_method||!emit_method||!dns_method)return JNI_FALSE;return dnsbunker_full_start(token,mtu)?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_org_dnsbunker_android_core_NativeFullTunnel_nativeInput(JNIEnv* e,jclass cls,jlong token,jbyteArray frame,jint n){
 if(n<20||n>65535||n>(*e)->GetArrayLength(e,frame))return JNI_FALSE;void* b=malloc(n);if(!b)return JNI_FALSE;(*e)->GetByteArrayRegion(e,frame,0,n,b);int ok=dnsbunker_full_input(token,b,n);free(b);return ok?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT void JNICALL Java_org_dnsbunker_android_core_NativeFullTunnel_nativeStop(JNIEnv* e,jclass cls,jlong token){dnsbunker_full_stop(token);}
