import http from "node:http";
import crypto from "node:crypto";

export class ChromeSession {
  constructor(pageId) {
    this.pageId = pageId;
    this.msgId = 0;
    this.socket = null;
    this.callbacks = new Map();
  }

  static async getActivePage() {
    return new Promise((resolve, reject) => {
      http
        .get(
          "http://host.docker.internal:9222/json/list",
          { headers: { Host: "localhost:9222" } },
          (res) => {
            let data = "";
            res.on("data", (chunk) => (data += chunk));
            res.on("end", () => {
              try {
                const list = JSON.parse(data);
                const page = list.find((p) => p.type === "page");
                if (page) resolve(new ChromeSession(page.id));
                else
                  reject(
                    new Error(
                      "No active browser page found. Make sure Chrome is running.",
                    ),
                  );
              } catch (err) {
                reject(err);
              }
            });
          },
        )
        .on("error", reject);
    });
  }

  connect() {
    return new Promise((resolve, reject) => {
      const key = crypto.randomBytes(16).toString("base64");
      const req = http.request({
        hostname: "host.docker.internal",
        port: 9222,
        path: `/devtools/page/${this.pageId}`,
        headers: {
          Host: "localhost:9222",
          Upgrade: "websocket",
          Connection: "Upgrade",
          "Sec-WebSocket-Key": key,
          "Sec-WebSocket-Version": "13",
        },
      });

      req.on("upgrade", (res, socket) => {
        this.socket = socket;
        let buffer = Buffer.alloc(0);

        socket.on("data", (chunk) => {
          buffer = Buffer.concat([buffer, chunk]);
          while (buffer.length >= 2) {
            const payloadLen = buffer[1] & 0x7f;
            let offset = 2;
            let realLen = payloadLen;
            if (payloadLen === 126) {
              if (buffer.length < 4) break;
              realLen = buffer.readUInt16BE(2);
              offset = 4;
            } else if (payloadLen === 127) {
              if (buffer.length < 10) break;
              realLen = Number(buffer.readBigUInt64BE(2));
              offset = 10;
            }
            if (buffer.length < offset + realLen) break;
            const data = buffer
              .subarray(offset, offset + realLen)
              .toString("utf8");
            buffer = buffer.subarray(offset + realLen);
            try {
              const parsed = JSON.parse(data);
              if (parsed.id && this.callbacks.has(parsed.id)) {
                const cb = this.callbacks.get(parsed.id);
                this.callbacks.delete(parsed.id);
                cb(parsed);
              }
            } catch (err) {
              console.error("JSON parse error:", err);
            }
          }
        });

        resolve();
      });

      req.on("error", reject);
      req.end();
    });
  }

  send(method, params = {}) {
    return new Promise((resolve) => {
      const id = ++this.msgId;
      this.callbacks.set(id, resolve);
      const msg = JSON.stringify({ id, method, params });
      const payload = Buffer.from(msg);
      const len = payload.length;
      let header;
      if (len < 126) {
        header = Buffer.from([0x81, 0x80 | len]);
      } else if (len < 65536) {
        header = Buffer.alloc(4);
        header[0] = 0x81;
        header[1] = 0x80 | 126;
        header.writeUInt16BE(len, 2);
      } else {
        header = Buffer.alloc(10);
        header[0] = 0x81;
        header[1] = 0x80 | 127;
        header.writeBigUInt64BE(BigInt(len), 2);
      }
      const mask = crypto.randomBytes(4);
      const masked = Buffer.alloc(len);
      for (let i = 0; i < len; i++) masked[i] = payload[i] ^ mask[i % 4];
      this.socket.write(Buffer.concat([header, mask, masked]));
    });
  }

  async eval(expression) {
    const res = await this.send("Runtime.evaluate", {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    return res.result?.result?.value;
  }

  async navigate(url) {
    await this.send("Page.navigate", { url });
    await new Promise((r) => setTimeout(r, 1000));
  }

  async waitFor(selector, timeoutMs = 5000) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      const exists = await this.eval(
        `Boolean(document.querySelector(${JSON.stringify(selector)}))`,
      );
      if (exists) return true;
      await new Promise((r) => setTimeout(r, 100));
    }
    throw new Error(`Timeout waiting for selector: ${selector}`);
  }

  async click(selector) {
    await this.waitFor(selector);
    await this.eval(
      `document.querySelector(${JSON.stringify(selector)})?.click()`,
    );
    await new Promise((r) => setTimeout(r, 600));
  }

  async type(selector, value) {
    await this.waitFor(selector);
    await this.eval(`
      (() => {
        const el = document.querySelector(${JSON.stringify(selector)});
        if (el) {
          const isTextarea = el.tagName === 'TEXTAREA';
          const proto = isTextarea ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
          const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
          if (setter) {
            setter.call(el, ${JSON.stringify(value)});
          } else {
            el.value = ${JSON.stringify(value)};
          }
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
          el.dispatchEvent(new Event('blur', { bubbles: true }));
        }
      })()
    `);
    await new Promise((r) => setTimeout(r, 200));
  }

  close() {
    if (this.socket) this.socket.end();
  }
}
