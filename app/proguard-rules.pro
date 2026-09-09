# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute source

# Evitar advertencias relacionadas con las bibliotecas de Google Play Services y Firebase
-dontwarn com.google.android.gms.**
-dontwarn com.google.firebase.**

# Mantener atributos usados por librerias Android/Firebase para anotaciones y generics.
-keepattributes Signature,*Annotation*

# Mantener constructores de Activities, Services, Receivers y Workers declarados por framework.
-keep public class com.controlparental.jerico.*Activity
-keep public class com.controlparental.jerico.*Service
-keep public class com.controlparental.jerico.*Receiver
-keep public class com.controlparental.jerico.*Worker

# Mantener helpers accedidos desde layouts XML o callbacks Android por nombre.
-keepclassmembers class com.controlparental.jerico.** {
    public void *(android.view.View);
}
