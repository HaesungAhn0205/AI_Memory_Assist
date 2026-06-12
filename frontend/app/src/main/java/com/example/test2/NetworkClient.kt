package com.example.test2

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {
    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val requestBuilder = original.newBuilder()
        
        // 유저 아이디가 있으면 헤더에 추가
        AppPreferences.userId?.let {
            requestBuilder.header("user-id", it)
        }
        
        val request = requestBuilder.build()
        val response = chain.proceed(request)
        
        val url = request.url.toString().substringAfterLast("/")
        AppPreferences.addLog("HTTP", "${request.method} /$url -> ${response.code}")
        
        response
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    // 초기값은 AppPreferences에서 가져온 host에 포트 결합
    private var _apiBaseUrl = "${AppPreferences.serverHost}:8000/"
    private var _vodBaseUrl = "${AppPreferences.serverHost}:8001/"

    val BASE_URL: String get() = _apiBaseUrl
    val VOD_URL: String get() = _vodBaseUrl

    // 8000번 포트용 Retrofit
    private var apiRetrofit = buildRetrofit(_apiBaseUrl)
    // 8001번 포트용 Retrofit
    private var vodRetrofit = buildRetrofit(_vodBaseUrl)

    var chatApiService: ChatApiService = apiRetrofit.create(ChatApiService::class.java)
        private set
    var videoApiService: VideoApiService = vodRetrofit.create(VideoApiService::class.java)
        private set
    var authApiService: AuthApiService = apiRetrofit.create(AuthApiService::class.java)
        private set
    var routineApiService: RoutineApiService = apiRetrofit.create(RoutineApiService::class.java)
        private set
    var objectsApiService: ObjectsApiService = apiRetrofit.create(ObjectsApiService::class.java)
        private set

    private fun buildRetrofit(url: String): Retrofit {
        return Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    fun updateBaseUrl(newUrl: String) {
        var hostPart = newUrl.trim().removeSuffix("/")
        
        if (!hostPart.startsWith("http://") && !hostPart.startsWith("https://")) {
            hostPart = "http://$hostPart"
        }
        
        val lastColon = hostPart.lastIndexOf(":")
        if (lastColon > hostPart.indexOf("://") + 1) {
            hostPart = hostPart.substring(0, lastColon)
        }
        
        AppPreferences.saveServerHost(hostPart)
        
        _apiBaseUrl = "$hostPart:8000/"
        _vodBaseUrl = "$hostPart:8001/"
        
        apiRetrofit = buildRetrofit(_apiBaseUrl)
        vodRetrofit = buildRetrofit(_vodBaseUrl)

        chatApiService = apiRetrofit.create(ChatApiService::class.java)
        videoApiService = vodRetrofit.create(VideoApiService::class.java)
        authApiService = apiRetrofit.create(AuthApiService::class.java)
        routineApiService = apiRetrofit.create(RoutineApiService::class.java)
        objectsApiService = apiRetrofit.create(ObjectsApiService::class.java)
        
        AppPreferences.addLog("NET", "Updated API: $_apiBaseUrl, VOD: $_vodBaseUrl")
    }
}
