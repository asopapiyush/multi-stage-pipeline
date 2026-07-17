const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 3000;
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
const PUBLIC_DIR = __dirname;

const MIME_TYPES = {
  '.html': 'text/html',
  '.css': 'text/css',
  '.js': 'text/javascript',
  '.json': 'application/json',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
};

const server = http.createServer((req, res) => {
  console.log(`${req.method} ${req.url}`);

  // Runtime config: generated fresh from process.env on every request, so changing
  // BACKEND_URL (e.g. a Render env var) takes effect on container restart, no rebuild.
  if (req.url === '/config.js') {
    res.statusCode = 200;
    res.setHeader('Content-Type', 'text/javascript');
    res.end(`window.APP_CONFIG = ${JSON.stringify({ BACKEND_URL })};`);
    return;
  }

  // Clean path to prevent directory traversal
  let filePath = path.join(PUBLIC_DIR, req.url === '/' ? 'index.html' : req.url);

  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.statusCode = 403;
    res.end('Forbidden');
    return;
  }

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      res.statusCode = 404;
      res.setHeader('Content-Type', 'text/plain');
      res.end('Not Found');
      return;
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';

    res.statusCode = 200;
    res.setHeader('Content-Type', contentType);

    const stream = fs.createReadStream(filePath);
    stream.pipe(res);
  });
});

server.listen(PORT, () => {
  console.log(`Server is running at http://localhost:${PORT}`);
  console.log(`Proxying backend at ${BACKEND_URL}`);
});
