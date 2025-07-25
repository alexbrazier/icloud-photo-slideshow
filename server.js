const express = require("express");
const fs = require("fs");
const path = require("path");

const PORT = process.env.PORT || 3000;
const app = express();

// Simple in-memory cache
let imagesCache = null;
let cacheTimestamp = 0;
const CACHE_TTL = 60 * 60 * 1000; // 1 hour

async function fetchAndCacheImages(albumId) {
  const webstream = await fetchApple(albumId, "webstream", {
    streamCtag: null,
  });
  const photoIds = webstream.photos.map((p) => {
    const derivatives = p.derivatives;
    const highestRes = Object.keys(derivatives)
      .map(Number)
      .sort((a, b) => a - b);
    return derivatives[highestRes[highestRes.length - 1]].checksum;
  });
  const webasseturls = await fetchApple(albumId, "webasseturls", {
    photoGuids: webstream.photos.map((p) => p.photoGuid),
  });
  const images = photoIds.map(
    (p) =>
      `https://${webasseturls.items[p].url_location}${webasseturls.items[p].url_path}`
  );
  imagesCache[albumId] = images;
  cacheTimestamp[albumId] = Date.now();
  return images;
}

app.get("/images", async (req, res) => {
  const now = Date.now();
  const albumId = req.query.albumId;
  if (!albumId) {
    return res
      .status(400)
      .json({ error: "albumId query parameter is required" });
  }
  // Use a cache per albumId
  if (!imagesCache) imagesCache = {};
  if (!cacheTimestamp) cacheTimestamp = {};
  const cacheKey = albumId;
  const cacheValid =
    imagesCache[cacheKey] && now - cacheTimestamp[cacheKey] < CACHE_TTL;
  const cacheExists = imagesCache[cacheKey];

  // Always return cache if it exists
  if (cacheExists) {
    res.json(imagesCache[cacheKey]);
    // If expired, refresh in background
    if (!cacheValid && !imagesCache[cacheKey + "_loading"]) {
      imagesCache[cacheKey + "_loading"] = true;
      (async () => {
        try {
          await fetchAndCacheImages(albumId);
        } catch (err) {
          // Optionally log error
        } finally {
          imagesCache[cacheKey + "_loading"] = false;
        }
      })();
    }
  } else {
    // If no cache, fetch and cache immediately
    try {
      const images = await fetchAndCacheImages(albumId);
      res.json(images);
    } catch (err) {
      res.status(502).json({
        error: "Failed to fetch images",
        details: err.message || err,
      });
    }
  }
});

app.get("/config.js", (req, res) => {
  const filePath = path.join(__dirname, "config.js");
  if (!fs.existsSync(filePath)) {
    res.status(404).type("text/plain").send("Config not found");
    return;
  }
  res.type("application/javascript").send(fs.readFileSync(filePath));
});

app.get("/", (req, res) => {
  const filePath = path.join(__dirname, "index.html");
  res.type("text/html").send(fs.readFileSync(filePath));
});

app.listen(PORT, (err) => {
  if (err) {
    console.error("Error starting server:", err.message);
    return;
  }
  console.log(`Express server running at http://localhost:${PORT}`);
});

// Update fetchApple to accept albumId
async function fetchApple(albumId, endpoint, data) {
  if (!albumId) throw new Error("No album ID configured");
  const url = `https://p153-sharedstreams.icloud.com/${albumId}/sharedstreams/${endpoint}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}
