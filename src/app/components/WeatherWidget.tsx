import React, { useState, useEffect } from "react";

interface WeatherData {
  temperature: number;
  condition: string;
  icon: string;
  location: string;
  rainChance?: number;
  dailyHigh?: number;
  dailyLow?: number;
}

const WeatherIcon = ({ icon }: { icon: string }) => {
  // Map MET Norway symbol codes to emojis
  const getWeatherEmoji = (symbolCode: string) => {
    const code = symbolCode.replace(/_day|_night|_polartwilight/g, "");

    const emojiMap: Record<string, string> = {
      clearsky: "☀️",
      fair: "🌤️",
      partlycloudy: "⛅",
      cloudy: "☁️",
      rainshowers: "🌦️",
      rainshowersandthunder: "⛈️",
      sleetshowers: "🌨️",
      snowshowers: "🌨️",
      rain: "🌧️",
      heavyrain: "🌧️",
      heavyrainandthunder: "⛈️",
      sleet: "🌨️",
      snow: "❄️",
      snowandthunder: "⛈️",
      fog: "🌫️",
      sleetshowersandthunder: "⛈️",
      snowshowersandthunder: "⛈️",
      rainandthunder: "⛈️",
      sleetandthunder: "⛈️",
      lightrainshowersandthunder: "⛈️",
      heavyrainshowersandthunder: "⛈️",
      lightssleetshowersandthunder: "⛈️",
      heavysleetshowersandthunder: "⛈️",
      lightssnowshowersandthunder: "⛈️",
      heavysnowshowersandthunder: "⛈️",
      lightrain: "🌧️",
      lightsleet: "🌨️",
      lightsnow: "❄️",
      lightrainandthunder: "⛈️",
      lightsleetandthunder: "⛈️",
      lightsnowandthunder: "⛈️",
      heavysleet: "🌨️",
      heavysnow: "❄️",
      lightrainshowers: "🌦️",
      heavyrainshowers: "🌦️",
      lightsleetshowers: "🌨️",
      heavysleetshowers: "🌨️",
      lightsnowshowers: "🌨️",
      heavysnowshowers: "🌨️",
    };

    return emojiMap[code] || "🌤️";
  };

  return (
    <div
      style={{
        fontSize: "28px",
        marginRight: "8px",
        width: "40px",
        height: "40px",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      {getWeatherEmoji(icon)}
    </div>
  );
};

export function WeatherWidget() {
  const [weather, setWeather] = useState<WeatherData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchWeather = async () => {
      try {
        setLoading(true);

        // Get user's location
        const position = await new Promise<GeolocationPosition>(
          (resolve, reject) => {
            navigator.geolocation.getCurrentPosition(resolve, reject);
          }
        );

        const { latitude, longitude } = position.coords;

        // Fetch weather data from our API route
        const response = await fetch(
          `/api/weather?lat=${latitude}&lon=${longitude}`
        );
        const data = await response.json();

        if (data.error) {
          throw new Error(data.error);
        }

        setWeather(data);
        setError(null);
      } catch (err) {
        console.error("Weather fetch error:", err);
        setError("Unable to load weather");
      } finally {
        setLoading(false);
      }
    };

    fetchWeather();
    // Refresh weather every 30 minutes
    const interval = setInterval(fetchWeather, 30 * 60 * 1000);

    return () => clearInterval(interval);
  }, []);

  if (loading) {
    return (
      <div
        style={{
          position: "fixed",
          top: "20px",
          left: "20px",
          background: "rgba(255, 255, 255, 0.7)",
          borderRadius: "12px",
          padding: "12px 16px",
          zIndex: 50,
          backdropFilter: "blur(10px)",
          boxShadow: "0 4px 12px rgba(0, 0, 0, 0.15)",
        }}
      >
        <div style={{ fontSize: "14px", color: "#666" }}>Loading...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div
        style={{
          position: "fixed",
          top: "20px",
          left: "20px",
          background: "rgba(255, 255, 255, 0.7)",
          borderRadius: "12px",
          padding: "12px 16px",
          zIndex: 50,
          backdropFilter: "blur(10px)",
          boxShadow: "0 4px 12px rgba(0, 0, 0, 0.15)",
        }}
      >
        <div style={{ fontSize: "14px", color: "#ff6b6b" }}>{error}</div>
      </div>
    );
  }

  if (!weather) return null;

  return (
    <div
      style={{
        position: "fixed",
        top: "20px",
        left: "20px",
        background: "rgba(255, 255, 255, 0.7)",
        borderRadius: "12px",
        padding: "12px 16px",
        zIndex: 50,
        backdropFilter: "blur(10px)",
        boxShadow: "0 4px 12px rgba(0, 0, 0, 0.15)",
        display: "flex",
        alignItems: "center",
        minWidth: "220px",
        opacity: 0.8,
      }}
    >
      <WeatherIcon icon={weather.icon} />
      <div>
        <div
          style={{
            fontSize: "18px",
            fontWeight: "bold",
            color: "#333",
            marginBottom: "2px",
          }}
        >
          {Math.round(weather.temperature)}°
          {weather.dailyHigh !== undefined &&
            weather.dailyLow !== undefined && (
              <span
                style={{
                  fontSize: "14px",
                  fontWeight: "normal",
                  color: "#666",
                  marginLeft: "6px",
                }}
              >
                H:{Math.round(weather.dailyHigh)}° L:
                {Math.round(weather.dailyLow)}°
              </span>
            )}
        </div>
        <div
          style={{
            fontSize: "12px",
            color: "#666",
            marginBottom: "2px",
          }}
        >
          {weather.condition}
        </div>
        {weather.rainChance !== undefined && (
          <div
            style={{
              fontSize: "11px",
              color: "#4a90e2",
              display: "flex",
              alignItems: "center",
            }}
          >
            💧 {weather.rainChance}%
          </div>
        )}
      </div>
    </div>
  );
}
