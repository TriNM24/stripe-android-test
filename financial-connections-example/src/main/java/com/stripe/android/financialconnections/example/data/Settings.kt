package com.stripe.android.financialconnections.example.data

import android.content.Context
import android.content.pm.PackageManager

/**
 * See [Configure the app](https://github.com/stripe/stripe-android/tree/master/example#configure-the-app)
 * for instructions on how to configure the example app before running it.
 */
data class Settings(
    val backendUrl: String,
) {
    constructor(context: Context) : this(
        getMetadata(context, METADATA_KEY_BACKEND_URL_KEY) ?: BASE_URL,
    )

    private companion object {
        /**
         * Return the manifest metadata value for the given key.
         */
        private fun getMetadata(
            context: Context,
            key: String
        ): String? {
            return context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
                .getString(key)
                .takeIf { it?.isNotBlank() == true }
        }

        /**
         * The base URL of the financial connections test backend
         *
         * Note: only necessary if not configured via `gradle.properties`.
         */
        private const val BASE_URL =
            "https://stripe-mobile-connections-example.glitch.me/"

        private const val METADATA_KEY_BACKEND_URL_KEY =
            "com.stripe.android.financialconnections.example.backend_url"
    }

}
