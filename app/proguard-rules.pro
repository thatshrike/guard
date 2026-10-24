# WorkManager instantiates workers by class name.
-keep class com.acousticguard.dispatch.DispatchWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# The foreground service is declared by name in AndroidManifest.xml.
-keep class com.acousticguard.monitoring.MonitoringService {
    public <init>();
}

# TensorFlow Lite Task Audio reaches the bundled model through its public Java API and JNI.
-keep class org.tensorflow.lite.task.audio.** { *; }
-keep class org.tensorflow.lite.support.audio.** { *; }
