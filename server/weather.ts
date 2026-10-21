// Use MET Norway API (no API key required)
type Period = {
  data: {
    instant: { details: { air_temperature?: number } };
    next_1_hours?: {
      summary?: { symbol_code?: string };
      details?: { precipitation_amount?: number };
    };
    next_6_hours?: { summary?: { symbol_code?: string } };
  };
};
type Forecast = { properties: { timeseries: Period[] } };

export type Weather = {
  temperature: number | undefined;
  condition: string;
  icon: string;
  location: string;
  rainChance: number;
  dailyHigh: number | undefined;
  dailyLow: number | undefined;
};

const CONDITIONS: Record<string, string> = {
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

// Convert symbol code to readable condition
function getConditionFromSymbol(symbolCode: string) {
  const code = symbolCode.replace(/_day|_night|_polartwilight/g, "");
  return CONDITIONS[code] || code.replace(/([A-Z])/g, " $1").toLowerCase();
}

export async function getWeather(lat: string, lon: string): Promise<Weather> {
  const response = await fetch(
    `https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${encodeURIComponent(lat)}&lon=${encodeURIComponent(lon)}`,
    {
      headers: {
        "User-Agent":
          "icloud-photo-slideshow/1.0 (https://github.com/alexbrazier/icloud-photo-slideshow)",
      },
    }
  );
  if (!response.ok) throw new Error("Failed to fetch weather data");

  const data = (await response.json()) as Forecast;
  const timeseries = data.properties.timeseries;
  const current = timeseries[0];
  const symbolCode =
    current.data.next_1_hours?.summary?.symbol_code ||
    current.data.next_6_hours?.summary?.symbol_code ||
    "unknown";

  // Rain chance, high and low from the next 24 hours
  const next24Hours = timeseries.slice(0, 24);
  let rainyPeriods = 0;
  const temperatures: number[] = [];
  for (const period of next24Hours) {
    if ((period.data.next_1_hours?.details?.precipitation_amount ?? 0) > 0)
      rainyPeriods++;
    const t = period.data.instant.details.air_temperature;
    if (typeof t === "number") temperatures.push(t);
  }

  return {
    temperature: current.data.instant.details.air_temperature,
    condition: getConditionFromSymbol(symbolCode),
    icon: symbolCode,
    location: `${parseFloat(lat).toFixed(1)}, ${parseFloat(lon).toFixed(1)}`,
    rainChance: Math.round((rainyPeriods / next24Hours.length) * 100),
    dailyHigh: temperatures.length ? Math.max(...temperatures) : undefined,
    dailyLow: temperatures.length ? Math.min(...temperatures) : undefined,
  };
}
