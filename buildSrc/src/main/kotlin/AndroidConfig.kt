object AndroidConfig {
    const val compileSdk = 36
    // Dynamic extension APKs need native interface defaults. Desugaring just the
    // host for older Android removes defaults their serializers rely on.
    const val minSdk = 26
    const val targetSdk = 34
    const val ndk = "26.1.10909125"
}
