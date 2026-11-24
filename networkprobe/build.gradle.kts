plugins { id("com.android.application") }
android {
 namespace="org.dnsbunker.android.networkprobe"
 compileSdk=36
 buildToolsVersion="36.0.0"
 defaultConfig { applicationId="org.dnsbunker.android.networkprobe";minSdk=29;targetSdk=36;versionCode=1;versionName="test-only" }
 compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
}
