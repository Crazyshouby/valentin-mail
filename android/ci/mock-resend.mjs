// Faux service Resend pour les tests : enregistre ce qu'il reçoit et, comme le
// vrai, va chercher les pièces jointes par leur lien.
import http from "node:http";
import { appendFileSync } from "node:fs";
import { createHash } from "node:crypto";
const LOG = process.env.MOCK_LOG || "resend.log";
const WORKER = process.env.WORKER_ORIGIN || "http://127.0.0.1:8787";
http.createServer((req, res) => {
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", async () => {
    const parsed = JSON.parse(body || "{}");
    const fetched = [];
    for (const att of parsed.attachments || []) {
      try {
        const u = new URL(att.path);
        const r = await fetch(WORKER + u.pathname + u.search);
        const buf = Buffer.from(await r.arrayBuffer());
        fetched.push({ filename: att.filename, status: r.status, bytes: buf.length, sha256: createHash("sha256").update(buf).digest("hex"), type: r.headers.get("content-type") });
      } catch (e) {
        fetched.push({ filename: att.filename, error: String(e) });
      }
    }
    appendFileSync(LOG, JSON.stringify({ auth: req.headers.authorization, body: parsed, fetched }) + "\n");
    if (String(parsed.subject || "").includes("REFUS")) {
      res.writeHead(403, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ statusCode: 403, name: "validation_error", message: "The valentinlarge.com domain is not verified." }));
      return;
    }
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ id: "mock-0001" }));
  });
}).listen(Number(process.env.MOCK_PORT || 9911), "0.0.0.0");
