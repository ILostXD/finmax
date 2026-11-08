package com.finmax.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.ApiClientFactory
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketConnectionFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

/**
 * The SDK's OkHttp client reads response bodies on the caller's thread, which throws
 * NetworkOnMainThreadException for any non-trivial response requested from the UI dispatcher.
 * Routing every request through Dispatchers.IO here keeps all call sites simple and safe.
 */
private class IoApiClient(private val delegate: ApiClient) : ApiClient() {
    override val baseUrl get() = delegate.baseUrl
    override val accessToken get() = delegate.accessToken
    override val clientInfo get() = delegate.clientInfo
    override val deviceInfo get() = delegate.deviceInfo
    override val httpClientOptions get() = delegate.httpClientOptions
    override val webSocket get() = delegate.webSocket

    override fun update(baseUrl: String?, accessToken: String?, clientInfo: ClientInfo, deviceInfo: DeviceInfo) =
        delegate.update(baseUrl, accessToken, clientInfo, deviceInfo)

    override suspend fun request(
        method: HttpMethod,
        pathTemplate: String,
        pathParameters: Map<String, Any?>,
        queryParameters: Map<String, Any?>,
        requestBody: Any?,
    ): RawResponse = withContext(Dispatchers.IO) {
        delegate.request(method, pathTemplate, pathParameters, queryParameters, requestBody)
    }
}

class IoApiClientFactory(private val base: ApiClientFactory) : ApiClientFactory {
    override fun create(
        baseUrl: String?,
        accessToken: String?,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
        httpClientOptions: HttpClientOptions,
        socketConnectionFactory: SocketConnectionFactory,
    ): ApiClient = IoApiClient(base.create(baseUrl, accessToken, clientInfo, deviceInfo, httpClientOptions, socketConnectionFactory))
}
