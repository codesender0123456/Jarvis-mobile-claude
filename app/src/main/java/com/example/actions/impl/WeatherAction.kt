package com.example.actions.impl

import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WeatherAction(private val client: OkHttpClient) : Action {

    override val name: String = "get_weather"
    override val description: String = "Fetches live weather reports, temperature, and conditions for a given city or coordinates."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "location" to ParamDefinition("string", "City name (e.g. London, New York, Tokyo)", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult = withContext(Dispatchers.IO) {
        val location = (args["location"] as? String)?.trim() ?: return@withContext ActionResult("Location required.", isError = true)

        try {
            // Geocoding via Open-Meteo geocoding API
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${java.net.URLEncoder.encode(location, "UTF-8")}&count=1"
            val geoReq = Request.Builder().url(geoUrl).build()
            val geoResp = client.newCall(geoReq).execute()
            val geoBody = geoResp.body?.string() ?: ""
            val geoJson = JSONObject(geoBody)
            val results = geoJson.optJSONArray("results")

            if (results == null || results.length() == 0) {
                return@withContext ActionResult("Could not locate atmospheric data for '$location', Sir.", isError = true)
            }

            val firstMatch = results.getJSONObject(0)
            val lat = firstMatch.getDouble("latitude")
            val lon = firstMatch.getDouble("longitude")
            val name = firstMatch.getString("name")
            val country = firstMatch.optString("country", "")

            // Fetch live weather
            val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
            val weatherReq = Request.Builder().url(weatherUrl).build()
            val weatherResp = client.newCall(weatherReq).execute()
            val weatherBody = weatherResp.body?.string() ?: ""
            val weatherJson = JSONObject(weatherBody)
            val current = weatherJson.getJSONObject("current")

            val temp = current.getDouble("temperature_2m")
            val humidity = current.getInt("relative_humidity_2m")
            val windSpeed = current.getDouble("wind_speed_10m")
            val weatherCode = current.getInt("weather_code")

            val condition = decodeWeatherCode(weatherCode)
            val spoken = "In $name, it is currently ${temp.toInt()}°C with $condition. Humidity is $humidity% and wind speed is ${windSpeed.toInt()} km/h."

            val cardData = mapOf(
                "city" to "$name $country",
                "temperature" to "$temp°C",
                "condition" to condition,
                "humidity" to "$humidity%",
                "wind" to "$windSpeed km/h"
            )

            ActionResult(spokenResult = spoken, cardData = cardData)
        } catch (e: Exception) {
            ActionResult("Atmospheric sensors report connection error: ${e.message}", isError = true)
        }
    }

    private fun decodeWeatherCode(code: Int): String {
        return when (code) {
            0 -> "clear skies"
            1, 2, 3 -> "partly cloudy skies"
            45, 48 -> "foggy conditions"
            51, 53, 55 -> "light drizzle"
            61, 63, 65 -> "rain"
            71, 73, 75 -> "snowfall"
            80, 81, 82 -> "rain showers"
            95, 96, 99 -> "thunderstorms"
            else -> "overcast conditions"
        }
    }
}
