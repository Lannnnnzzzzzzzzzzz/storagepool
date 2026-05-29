package com.example.data.remote

import android.util.Log
import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object SupabaseClient {
    private const val TAG = "SupabaseClient"

    val supabaseUrl: String = getSafeConfig(BuildConfig.SUPABASE_URL, "https://placeholder-project.supabase.co").trim().removeSuffix("/")
    val supabaseAnonKey: String = getSafeConfig(BuildConfig.SUPABASE_ANON_KEY, "placeholder-anon-key").trim()

    init {
        Log.d(TAG, "Initializing SupabaseClient with URL: $supabaseUrl")
    }

    private var userToken: String? = null

    fun setSessionToken(token: String?) {
        userToken = token
    }

    private fun getSafeConfig(value: String?, fallback: String): String {
        return if (value.isNullOrEmpty() || value.contains("SUPABASE") || value.contains("your-supabase")) {
            fallback
        } else {
            value
        }
    }

    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val headerInterceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
            .header("apikey", supabaseAnonKey)
            .header("Authorization", "Bearer ${userToken ?: supabaseAnonKey}")
            .header("Prefer", "return=representation")
            
        chain.proceed(builder.build())
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(headerInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val rootUrl: String = if (supabaseUrl.startsWith("http")) supabaseUrl else "https://$supabaseUrl"

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("$rootUrl/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val authApi: SupabaseAuthApi by lazy {
        retrofit.create(SupabaseAuthApi::class.java)
    }

    val dbApi: SupabaseDbApi by lazy {
        retrofit.create(SupabaseDbApi::class.java)
    }
}
