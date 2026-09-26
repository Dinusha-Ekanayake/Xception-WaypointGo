// Test-only TCP fault injection. The application never imports this module.
import net from "node:net";
import http from "node:http";
import { randomBytes } from "node:crypto";

export async function databaseProxy(connection: string) {
  const target = new URL(connection);
  // Hosted TLS URLs must retain their real hostname for certificate verification.
  if (!["localhost", "127.0.0.1"].includes(target.hostname) || (target.searchParams.has("sslmode") && target.searchParams.get("sslmode") !== "disable")) return undefined;
  let paused = false;
  const sockets = new Set<net.Socket>();
  const disconnect = () => { for (const socket of sockets) socket.destroy(); };
  const server = net.createServer((client) => {
    if (paused) { client.destroy(); return; }
    const upstream = net.connect(Number(target.port || 5432), target.hostname);
    for (const socket of [client, upstream]) {
      sockets.add(socket);
      socket.on("close", () => sockets.delete(socket));
      socket.on("error", () => { client.destroy(); upstream.destroy(); });
    }
    client.pipe(upstream).pipe(client);
    client.on("close", () => upstream.destroy());
    upstream.on("close", () => client.destroy());
  });
  await new Promise<void>((resolve, reject) => { server.once("error", reject); server.listen(0, "127.0.0.1", resolve); });
  const token = randomBytes(24).toString("hex");
  const control = http.createServer((req, res) => {
    if (req.method !== "POST" || req.headers.authorization !== `Bearer ${token}` || !["/pause", "/resume"].includes(req.url || "")) { res.writeHead(404).end(); return; }
    paused = req.url === "/pause";
    if (paused) disconnect();
    res.writeHead(200).end("ok");
  });
  await new Promise<void>((resolve, reject) => { control.once("error", reject); control.listen(0, "127.0.0.1", resolve); });
  const url = new URL(connection);
  url.hostname = "127.0.0.1";
  url.port = String((server.address() as net.AddressInfo).port);
  return {
    url: url.toString(),
    control: `http://127.0.0.1:${(control.address() as net.AddressInfo).port}`,
    token,
    close: async () => {
      disconnect();
      control.closeAllConnections();
      await Promise.all([new Promise<void>((resolve) => server.close(() => resolve())), new Promise<void>((resolve) => control.close(() => resolve()))]);
    },
  };
}
