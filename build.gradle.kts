
    plugins {
        alias(libs.plugins.android.application) apply false
        alias(libs.plugins.kotlin.android) apply false
        // Keep this one. It works fine.
        alias(libs.plugins.google.gms.google.services) apply false
    }
