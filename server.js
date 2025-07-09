const http = require("http");
const fs = require("fs");
const path = require("path");
const httpProxy = require("http-proxy");

const PORT = process.env.PORT || 3000;

// Create a proxy server for /api requests
const apiProxy = httpProxy.createProxyServer({
  target: "https://p153-sharedstreams.icloud.com",
  changeOrigin: true,
  secure: true,
  // Optionally, you can add more options here
});

const server = http.createServer((req, res) => {
  if (req.url.startsWith("/api/")) {
    // Forward /api requests to the target
    req.url = req.url.replace(/^\/api/, ""); // Remove /api prefix if needed
    apiProxy.web(req, res, {}, (err) => {
      res.writeHead(502, { "Content-Type": "text/plain" });
      res.end("Proxy error");
    });
    return;
  }
  if (req.url === "/config.js") {
    const filePath = path.join(__dirname, "config.js");
    fs.readFile(filePath, (err, data) => {
      if (err) {
        res.writeHead(404, { "Content-Type": "text/plain" });
        res.end("Config not found");
        return;
      }
      res.writeHead(200, { "Content-Type": "application/javascript" });
      res.end(data);
    });
    return;
  }
  if (req.url === "/") {
    const filePath = path.join(__dirname, "index.html");
    fs.readFile(filePath, (err, data) => {
      if (err) {
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal Server Error");
        return;
      }
      res.writeHead(200, { "Content-Type": "text/html" });
      res.end(data);
    });
  } else {
    res.writeHead(404, { "Content-Type": "text/plain" });
    res.end("Not Found");
  }
});

server.listen(PORT, () => {
  console.log(`Server running at http://localhost:${PORT}`);
});
