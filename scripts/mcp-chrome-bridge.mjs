import http from "node:http";

const server = http.createServer((req, res) => {
  const options = {
    hostname: "host.docker.internal",
    port: 9222,
    path: req.url,
    method: req.method,
    headers: {
      ...req.headers,
      host: "localhost:9222",
    },
  };

  const proxyReq = http.request(options, (proxyRes) => {
    // Rewrite websocket debug URL ports if present
    let body = [];
    proxyRes.on("data", (chunk) => body.push(chunk));
    proxyRes.on("end", () => {
      let data = Buffer.concat(body).toString();
      // Rewrite host.docker.internal or localhost:9222 to 127.0.0.1:9333 so MCP tool connects to bridge
      data = data.replace(/localhost:9222/g, "127.0.0.1:9333");
      data = data.replace(/host\.docker\.internal:9222/g, "127.0.0.1:9333");

      const headers = { ...proxyRes.headers };
      delete headers["content-length"];
      res.writeHead(proxyRes.statusCode, headers);
      res.end(data);
    });
  });

  proxyReq.on("error", (err) => {
    res.writeHead(502);
    res.end(err.message);
  });

  req.pipe(proxyReq);
});

// Proxy WebSocket upgrade
server.on("upgrade", (req, clientSocket, head) => {
  const options = {
    hostname: "host.docker.internal",
    port: 9222,
    path: req.url,
    headers: {
      ...req.headers,
      host: "localhost:9222",
    },
  };

  const proxyReq = http.request(options);
  proxyReq.on("upgrade", (proxyRes, targetSocket) => {
    clientSocket.write(
      `HTTP/1.1 101 Switching Protocols\r\n` +
        `Upgrade: websocket\r\n` +
        `Connection: Upgrade\r\n` +
        `Sec-WebSocket-Accept: ${proxyRes.headers["sec-websocket-accept"]}\r\n\r\n`,
    );
    targetSocket.pipe(clientSocket);
    clientSocket.pipe(targetSocket);
  });

  proxyReq.on("error", (err) => {
    clientSocket.end(`HTTP/1.1 502 Bad Gateway\r\n\r\n${err.message}`);
  });

  proxyReq.end();
});

server.listen(9333, "127.0.0.1", () => {
  console.log(
    "MCP Chrome Bridge listening on 127.0.0.1:9333 -> host.docker.internal:9222",
  );
});
