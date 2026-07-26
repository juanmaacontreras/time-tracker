plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Lee app/google-services.json y genera los recursos que Firebase necesita en
    // tiempo de compilación. Sin ese archivo el build falla — por eso se agregó
    // recién después de tenerlo commiteado.
    id("com.google.gms.google-services") version "4.4.2" apply false
}
