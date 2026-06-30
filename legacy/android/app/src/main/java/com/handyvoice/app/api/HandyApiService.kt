package com.handyvoice.app.api

import retrofit2.Response
import retrofit2.http.*

interface HandyApiService {
    
    @POST("auth/token")
    suspend fun getToken(
        @Header("X-Application-Key") apiKey: String,
        @Body request: TokenRequest
    ): Response<TokenResponse>
    
    @GET("info/device")
    suspend fun getDeviceInfo(
        @Header("X-Application-Key") apiKey: String,
        @Header("X-Connection-Key") connectionKey: String
    ): Response<DeviceInfo>
    
    @PUT("hamp/start")
    suspend fun startDevice(
        @Header("X-Application-Key") apiKey: String,
        @Header("X-Connection-Key") connectionKey: String
    ): Response<HampResponse>
    
    @PUT("hamp/stop")
    suspend fun stopDevice(
        @Header("X-Application-Key") apiKey: String,
        @Header("X-Connection-Key") connectionKey: String
    ): Response<HampResponse>
    
    @PUT("hamp/velocity")
    suspend fun setVelocity(
        @Header("X-Application-Key") apiKey: String,
        @Header("X-Connection-Key") connectionKey: String,
        @Body request: VelocityRequest
    ): Response<HampResponse>
    
    @PUT("slide/min")
    suspend fun setSlideMin(
        @Header("X-Application-Key") apiKey: String,
        @Header("X-Connection-Key") connectionKey: String,
        @Body request: SlideRequest
    ): Response<SlideResponse>
    
    @PUT("slide/max")
    suspend fun setSlideMax(
        @Header("X-Application-Key") apiKey: String,
        @Header("X-Connection-Key") connectionKey: String,
        @Body request: SlideRequest
    ): Response<SlideResponse>
    
    @GET("slide")
    suspend fun getSlide(
        @Header("X-Application-Key") apiKey: String,
        @Header("X-Connection-Key") connectionKey: String
    ): Response<SlideInfo>
    
    @GET("hamp/velocity")
    suspend fun getVelocity(
        @Header("X-Application-Key") apiKey: String,
        @Header("X-Connection-Key") connectionKey: String
    ): Response<VelocityInfo>
}

// Request/Response data classes
data class TokenRequest(val connectionKey: String)
data class TokenResponse(val token: String)

data class DeviceInfo(
    val fwVersion: String?,
    val fwStatus: Int?,
    val hwVersion: Int?,
    val model: Int?,
    val branch: Int?
)

data class HampResponse(val result: Int)

data class VelocityRequest(val velocity: Double)
data class VelocityInfo(val velocity: Double)

data class SlideRequest(val position: Double)
data class SlideResponse(val result: Int)
data class SlideInfo(val min: Double, val max: Double)
