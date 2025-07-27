import { NextRequest, NextResponse } from "next/server";

// Use MET Norway API (no API key required)
export async function GET(req: NextRequest) {
  const { searchParams } = new URL(req.url);
  const lat = searchParams.get("lat");
  const lon = searchParams.get("lon");

  if (!lat || !lon) {
    return NextResponse.json(
      { error: "Latitude and longitude are required" },
      { status: 400 }
    );
  }

  try {
    // Fetch weather data from MET Norway API
    const response = await fetch(
      `https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${lat}&lon=${lon}`,
      {
        headers: {
          "User-Agent":
            "icloud-photo-slideshow/1.0 (https://github.com/alexbrazier/icloud-photo-slideshow)",
        },
      }
    );

    if (!response.ok) {
      throw new Error("Failed to fetch weather data");
    }

    const data = await response.json();
    const current = data.properties.timeseries[0];
    const currentData = current.data.instant.details;
    const next1h = current.data.next_1_hours;
    const next6h = current.data.next_6_hours;

    // Get weather symbol (condition)
    const symbolCode =
      next1h?.summary?.symbol_code || next6h?.summary?.symbol_code || "unknown";

    // Calculate rain chance from next 24 hours
    let rainChance = 0;
    const next24Hours = data.properties.timeseries.slice(0, 24);
    const rainyPeriods = next24Hours.filter(
      (period: {
        data: {
          next_1_hours?: { details?: { precipitation_amount?: number } };
        };
      }) => {
        const precipitation =
          period.data.next_1_hours?.details?.precipitation_amount || 0;
        return precipitation > 0;
      }
    );
    rainChance = Math.round((rainyPeriods.length / next24Hours.length) * 100);

    // Calculate daily high and low from next 24 hours
    const temperatures = next24Hours.map(
      (period: {
        data: { instant: { details: { air_temperature: number } } };
      }) => period.data.instant.details.air_temperature
    );
    const dailyHigh = Math.max(...temperatures);
    const dailyLow = Math.min(...temperatures);

    // Convert symbol code to readable condition
    const getConditionFromSymbol = (symbolCode: string) => {
      const code = symbolCode.replace(/_day|_night|_polartwilight/g, "");
      const conditions: Record<string, string> = {
        clearsky: "Clear sky",
        fair: "Fair",
        partlycloudy: "Partly cloudy",
        cloudy: "Cloudy",
        rainshowers: "Rain showers",
        rainshowersandthunder: "Rain showers and thunder",
        sleetshowers: "Sleet showers",
        snowshowers: "Snow showers",
        rain: "Rain",
        heavyrain: "Heavy rain",
        heavyrainandthunder: "Heavy rain and thunder",
        sleet: "Sleet",
        snow: "Snow",
        snowandthunder: "Snow and thunder",
        fog: "Fog",
        sleetshowersandthunder: "Sleet showers and thunder",
        snowshowersandthunder: "Snow showers and thunder",
        rainandthunder: "Rain and thunder",
        sleetandthunder: "Sleet and thunder",
        lightrainshowersandthunder: "Light rain showers and thunder",
        heavyrainshowersandthunder: "Heavy rain showers and thunder",
        lightssleetshowersandthunder: "Light sleet showers and thunder",
        heavysleetshowersandthunder: "Heavy sleet showers and thunder",
        lightssnowshowersandthunder: "Light snow showers and thunder",
        heavysnowshowersandthunder: "Heavy snow showers and thunder",
        lightrain: "Light rain",
        lightsleet: "Light sleet",
        lightsnow: "Light snow",
        lightrainandthunder: "Light rain and thunder",
        lightsleetandthunder: "Light sleet and thunder",
        lightsnowandthunder: "Light snow and thunder",
        heavysleet: "Heavy sleet",
        heavysnow: "Heavy snow",
        lightrainshowers: "Light rain showers",
        heavyrainshowers: "Heavy rain showers",
        lightsleetshowers: "Light sleet showers",
        heavysleetshowers: "Heavy sleet showers",
        lightsnowshowers: "Light snow showers",
        heavysnowshowers: "Heavy snow showers",
      };
      return conditions[code] || code.replace(/([A-Z])/g, " $1").toLowerCase();
    };

    const weatherData = {
      temperature: currentData.air_temperature,
      condition: getConditionFromSymbol(symbolCode),
      icon: symbolCode, // We'll use this to determine emoji
      location: `${parseFloat(lat).toFixed(1)}, ${parseFloat(lon).toFixed(1)}`,
      rainChance,
      dailyHigh,
      dailyLow,
    };

    return NextResponse.json(weatherData);
  } catch (error) {
    console.error("Weather API error:", error);
    return NextResponse.json(
      { error: "Failed to fetch weather data" },
      { status: 500 }
    );
  }
}
