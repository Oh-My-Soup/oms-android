// Test-vector generator for the oms collab v5 wire protocol.
// Run: /usr/bin/node collab-vectors.mjs
//
// Faithful ports (logic copied, not re-designed) of:
//   packages/coding-agent/src/collab/protocol.ts  (normalizeRelayBaseUrl, formatCollabLink, formatCollabWebLink, parseCollabLink, packEnvelope)
//   packages/coding-agent/src/collab/crypto.ts    (sealSerialized / open: [12B IV][ciphertext||16B tag], no AAD)
//   packages/wire/src/collab-stream.ts            (diffCollabStream / applyCollabStream)
// Crypto is computed twice (WebCrypto subtle and node:crypto createCipheriv) and cross-checked.

import * as nodeCrypto from "node:crypto";
import { isDeepStrictEqual } from "node:util";

const DEFAULT_RELAY_URL = "wss://my.omp.sh";
const ROOM_KEY_BYTES = 32;
const WRITE_TOKEN_BYTES = 16;
const ENVELOPE_HEADER_LENGTH = 4;
const IV_LENGTH = 12;

const ROOM_PATH_RE = /^(.*)\/r\/([A-Za-z0-9_-]{10,64})(?:\.([A-Za-z0-9_-]+))?$/;
const BARE_LINK_RE = /^([A-Za-z0-9_-]{10,64})[#.]([A-Za-z0-9_-]+)$/;
const B64URL_RE = /^[A-Za-z0-9_-]+$/;

function normalizeRelayBaseUrl(relayUrl) {
	let url;
	try {
		url = new URL(relayUrl);
	} catch {
		return { error: `Invalid relay URL: ${relayUrl}` };
	}
	let scheme;
	switch (url.protocol) {
		case "wss:":
		case "https:":
			scheme = "wss:";
			break;
		case "ws:":
		case "http:":
			scheme = "ws:";
			break;
		default:
			return { error: `Unsupported relay URL scheme: ${url.protocol}` };
	}
	const port = url.port ? `:${url.port}` : "";
	const path = url.pathname.replace(/\/+$/, "");
	return { baseUrl: `${scheme}//${url.hostname}${port}${path}` };
}

function formatCollabLink(relayUrl, roomId, key, writeToken) {
	const normalized = normalizeRelayBaseUrl(relayUrl);
	if ("error" in normalized) throw new Error(normalized.error);
	const secret = writeToken ? Buffer.concat([key, writeToken]) : Buffer.from(key);
	const keyText = secret.toString("base64url");
	if (normalized.baseUrl === DEFAULT_RELAY_URL) return `${roomId}.${keyText}`;
	const compact = normalized.baseUrl.startsWith("wss://") ? normalized.baseUrl.slice("wss://".length) : normalized.baseUrl;
	return `${compact}/r/${roomId}.${keyText}`;
}

function normalizeCollabWebBaseUrl(relayUrl, webUrl) {
	const explicitWebUrl = webUrl?.trim();
	if (!explicitWebUrl) {
		const normalized = normalizeRelayBaseUrl(relayUrl);
		if ("error" in normalized) throw new Error(normalized.error);
		return normalized.baseUrl === DEFAULT_RELAY_URL ? `https://${normalized.baseUrl.slice("wss://".length)}` : null;
	}
	const url = new URL(explicitWebUrl);
	const path = url.pathname.replace(/\/+$/, "");
	return `${url.origin}${path}`;
}

function formatCollabWebLink(relayUrl, roomId, key, writeToken, webUrl) {
	const webBaseUrl = normalizeCollabWebBaseUrl(relayUrl, webUrl);
	if (webBaseUrl === null) return null;
	return `${webBaseUrl}/#${formatCollabLink(relayUrl, roomId, key, writeToken)}`;
}

function parseCollabLink(link) {
	let text = link.trim().replace(/%23/gi, "#");
	const bare = BARE_LINK_RE.exec(text);
	if (bare) text = `${DEFAULT_RELAY_URL}/r/${bare[1]}.${bare[2]}`;
	else if (!/^[A-Za-z][A-Za-z0-9+.-]*:\/\//.test(text)) text = `wss://${text}`;
	let url;
	try {
		url = new URL(text);
	} catch {
		return { error: `Invalid collab link: ${link}` };
	}
	if ((url.protocol === "http:" || url.protocol === "https:") && url.hash) {
		const inner = url.hash.startsWith("#") ? url.hash.slice(1) : url.hash;
		const parsed = parseCollabLink(inner);
		if (!("error" in parsed)) return parsed;
	}
	const match = ROOM_PATH_RE.exec(url.pathname);
	const normalized = normalizeRelayBaseUrl(`${url.origin}${match?.[1] ?? ""}`);
	if ("error" in normalized) return normalized;
	if (!match) {
		const inner = url.hash.startsWith("#") ? url.hash.slice(1) : url.hash;
		if (inner && url.protocol !== "http:" && url.protocol !== "https:") return parseCollabLink(inner);
		return { error: "Collab link must contain a /r/<roomId> path" };
	}
	const roomId = match[2];
	const fragment = match[3] ?? (url.hash.startsWith("#") ? url.hash.slice(1) : url.hash);
	if (!fragment) return { error: "Collab link is missing the <key> part" };
	const secret = B64URL_RE.test(fragment) ? new Uint8Array(Buffer.from(fragment, "base64url")) : null;
	if (!secret || (secret.byteLength !== ROOM_KEY_BYTES && secret.byteLength !== ROOM_KEY_BYTES + WRITE_TOKEN_BYTES)) {
		return { error: "Collab link key must be 32 (view) or 48 (full) base64url bytes" };
	}
	const key = secret.subarray(0, ROOM_KEY_BYTES);
	const writeToken = secret.byteLength > ROOM_KEY_BYTES ? secret.subarray(ROOM_KEY_BYTES) : undefined;
	return { wsUrl: `${normalized.baseUrl}/r/${roomId}`, roomId, key, writeToken };
}

function packEnvelope(peerId, sealed) {
	const out = new Uint8Array(ENVELOPE_HEADER_LENGTH + sealed.byteLength);
	new DataView(out.buffer).setUint32(0, peerId, false);
	out.set(sealed, ENVELOPE_HEADER_LENGTH);
	return out;
}

// ── collab-stream.ts ───────────────────────────────────────────────────────
function record(value) {
	return typeof value === "object" && value !== null && !Array.isArray(value) ? value : null;
}
function diffCollabStream(previous, next) {
	const { content, ...metadata } = next;
	const changes = [];
	for (let index = 0; index < content.length; index++) {
		const value = content[index];
		if (value === previous.content[index]) continue;
		const block = record(value);
		const before = record(previous.content[index]);
		const field = block?.type === "text" ? "text" : block?.type === "thinking" ? "thinking" : null;
		if (field !== null && before !== null && before.type === block?.type) {
			const text = block?.[field];
			const old = before[field];
			if (typeof text === "string" && typeof old === "string" && text.startsWith(old)) {
				const properties = { ...block };
				delete properties[field];
				changes.push({ index, kind: "append", field, offset: old.length, text: text.slice(old.length), properties });
				continue;
			}
		}
		changes.push({ index, kind: "replace", value });
	}
	return { metadata, contentLength: content.length, changes };
}
function applyCollabStream(previous, patch) {
	const content = previous.content.slice(0, patch.contentLength);
	for (const change of patch.changes) {
		if (change.index < 0 || change.index >= patch.contentLength) return null;
		if (change.kind === "replace") {
			content[change.index] = change.value;
			continue;
		}
		const before = record(content[change.index]);
		const text = before?.[change.field];
		if (typeof text !== "string" || text.length !== change.offset) return null;
		content[change.index] = { ...change.properties, [change.field]: text + change.text };
	}
	if (content.length !== patch.contentLength) return null;
	for (let index = 0; index < content.length; index++) if (content[index] === undefined) return null;
	return { ...patch.metadata, content };
}

// ── fixtures ───────────────────────────────────────────────────────────────
const hex = bytes => Buffer.from(bytes).toString("hex");
const seq = (start, n) => Uint8Array.from({ length: n }, (_, i) => (start + i) & 0xff);

const KEY = seq(0x00, 32); // 000102…1f
const WRITE_TOKEN = seq(0xa0, 16); // a0a1…af
const ROOM_ID_BYTES = seq(0x10, 16); // 101112…1f
const ROOM_ID = Buffer.from(ROOM_ID_BYTES).toString("base64url");
const IV = seq(0x00, 12); // 000102…0b

async function sealWebCrypto(rawKey, iv, plaintextString) {
	const key = await crypto.subtle.importKey("raw", rawKey, "AES-GCM", false, ["encrypt", "decrypt"]);
	const plaintext = new TextEncoder().encode(plaintextString);
	const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, plaintext));
	const out = new Uint8Array(IV_LENGTH + ct.byteLength);
	out.set(iv, 0);
	out.set(ct, IV_LENGTH);
	return out;
}
function sealNode(rawKey, iv, plaintextString) {
	const cipher = nodeCrypto.createCipheriv("aes-256-gcm", rawKey, iv); // 16-byte tag, no AAD
	const ct = Buffer.concat([cipher.update(plaintextString, "utf8"), cipher.final()]);
	return Buffer.concat([Buffer.from(iv), ct, cipher.getAuthTag()]);
}
async function openWebCrypto(rawKey, sealed) {
	const key = await crypto.subtle.importKey("raw", rawKey, "AES-GCM", false, ["decrypt"]);
	const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: sealed.subarray(0, 12) }, key, sealed.subarray(12));
	return new TextDecoder().decode(pt);
}

const out = [];
const log = (...a) => out.push(a.join(" "));

log("## Fixed inputs");
log("key (32B)          =", hex(KEY));
log("writeToken (16B)   =", hex(WRITE_TOKEN));
log("writeToken b64url  =", Buffer.from(WRITE_TOKEN).toString("base64url"));
log("roomId bytes (16B) =", hex(ROOM_ID_BYTES));
log("roomId (b64url)    =", ROOM_ID);
log("iv (12B)           =", hex(IV));
log("");

log("## Formatted links (formatCollabLink / formatCollabWebLink)");
const fullDefault = formatCollabLink(DEFAULT_RELAY_URL, ROOM_ID, KEY, WRITE_TOKEN);
const viewDefault = formatCollabLink(DEFAULT_RELAY_URL, ROOM_ID, KEY);
log("full, default relay        :", fullDefault);
log("view, default relay        :", viewDefault);
log("full web, default relay    :", formatCollabWebLink(DEFAULT_RELAY_URL, ROOM_ID, KEY, WRITE_TOKEN, ""));
log("view web, default relay    :", formatCollabWebLink(DEFAULT_RELAY_URL, ROOM_ID, KEY, undefined, ""));
log("full, wss://relay.example.com:8443/infra/oms/ :", formatCollabLink("wss://relay.example.com:8443/infra/oms/", ROOM_ID, KEY, WRITE_TOKEN));
log("full, ws://10.66.66.8      :", formatCollabLink("ws://10.66.66.8", ROOM_ID, KEY, WRITE_TOKEN));
log("web for ws://10.66.66.8 (no webUrl) :", formatCollabWebLink("ws://10.66.66.8", ROOM_ID, KEY, WRITE_TOKEN, ""));
log("web for ws://10.66.66.8 + webUrl http://10.66.66.8 :", formatCollabWebLink("ws://10.66.66.8", ROOM_ID, KEY, WRITE_TOKEN, "http://10.66.66.8"));
log("web for relay.example.com + webUrl https://web.example/tools/collab/ :", formatCollabWebLink("wss://relay.example.com", ROOM_ID, KEY, WRITE_TOKEN, "https://web.example/tools/collab/"));
log("");

const keyText = Buffer.concat([KEY, WRITE_TOKEN]).toString("base64url");
const viewKeyText = Buffer.from(KEY).toString("base64url");
const linkInputs = [
	`${ROOM_ID}.${keyText}`,
	`${ROOM_ID}#${viewKeyText}`,
	`  https://my.omp.sh/#${ROOM_ID}.${keyText}  `,
	`https://my.omp.sh/#${ROOM_ID}%23${keyText}`,
	`my.omp.sh/#${ROOM_ID}.${keyText}`,
	`relay.example.com:8443/r/${ROOM_ID}.${keyText}`,
	`wss://relay.example.com:443/infra/oms/r/${ROOM_ID}.${viewKeyText}`,
	`https://Relay.Example.com/r/${ROOM_ID}#${keyText}`,
	`http://relay.example.com:80/r/${ROOM_ID}.${keyText}`,
	`ws://10.66.66.8/r/${ROOM_ID}.${keyText}`,
	`ws://10.66.66.8/infra/relay/r/${ROOM_ID}.${keyText}`,
	`https://web.example/collab/#relay.example.com/r/${ROOM_ID}.${keyText}`,
	`http://10.66.66.8/#ws://10.66.66.8/r/${ROOM_ID}.${keyText}`,
	`https://web.example/tools/collab/#wss://relay.example/infra/oms/r/${ROOM_ID}.${keyText}`,
	`ftp://10.66.66.8/r/${ROOM_ID}.${keyText}`,
	`${ROOM_ID}.${keyText.slice(0, 40)}`,
	`https://web.example/#not-a-collab-link`,
];
log("## Parsed links (parseCollabLink)");
for (const input of linkInputs) {
	const parsed = parseCollabLink(input);
	log("input :", JSON.stringify(input));
	if ("error" in parsed) {
		log("  error:", parsed.error);
	} else {
		log("  wsUrl     :", parsed.wsUrl);
		log("  connect   :", `${parsed.wsUrl}?role=guest`);
		log("  roomId    :", parsed.roomId);
		log("  key       :", hex(parsed.key));
		log("  writeToken:", parsed.writeToken ? `${hex(parsed.writeToken)} (hello.writeToken="${Buffer.from(parsed.writeToken).toString("base64url")}")` : "(none: view-only link)");
	}
}
log("");

log("## AES-256-GCM seal vectors (no AAD, 12B IV, 16B tag appended)");
const frames = [
	{
		label: "guest hello (full link, paged history)",
		json: JSON.stringify({ t: "hello", proto: 5, name: "android", history: "paged", writeToken: Buffer.from(WRITE_TOKEN).toString("base64url") }),
		peer: 0,
	},
	{ label: "guest prompt", json: JSON.stringify({ t: "prompt", text: "héllo 👋" }), peer: 0 },
	{ label: "guest ui-response cancel (value omitted)", json: JSON.stringify({ t: "ui-response", reqId: 7 }), peer: 0 },
	{ label: "guest snapshot-ack", json: JSON.stringify({ t: "snapshot-ack", chunks: 1 }), peer: 0 },
	{ label: "host ui-request-end targeted at peer 3", json: JSON.stringify({ t: "ui-request-end", reqId: 7 }), peer: 3 },
];
for (const frame of frames) {
	const sealed = await sealWebCrypto(KEY, IV, frame.json);
	const nodeSealed = sealNode(KEY, IV, frame.json);
	if (hex(sealed) !== hex(nodeSealed)) throw new Error(`WebCrypto/node mismatch for ${frame.label}`);
	const roundTrip = await openWebCrypto(KEY, sealed);
	if (roundTrip !== frame.json) throw new Error("round trip failed");
	const ptBytes = Buffer.from(frame.json, "utf8");
	const envelope = packEnvelope(frame.peer, sealed);
	log(`### ${frame.label}`);
	log("plaintext (UTF-8) :", frame.json);
	log("plaintext hex     :", hex(ptBytes), `(${ptBytes.length} bytes)`);
	log("ciphertext hex    :", hex(sealed.subarray(12, sealed.length - 16)));
	log("tag hex           :", hex(sealed.subarray(sealed.length - 16)));
	log("sealed hex        :", hex(sealed), `(${sealed.length} bytes = 12 + ${ptBytes.length} + 16)`);
	log("envelope hex      :", hex(envelope), `(peerId ${frame.peer})`);
	log("");
}

// Tamper check: flipping one tag bit must fail authentication.
{
	const sealed = await sealWebCrypto(KEY, IV, frames[0].json);
	sealed[sealed.length - 1] ^= 0x01;
	let failed = false;
	try {
		await openWebCrypto(KEY, sealed);
	} catch {
		failed = true;
	}
	log("tamper check (last tag bit flipped) -> decrypt fails:", failed);
	log("");
}

log("## applyCollabStream vector");
const t0 = { type: "text", text: "Hel" };
const baseline = {
	role: "assistant",
	content: [{ type: "thinking", thinking: "Let me" }, t0],
	api: "anthropic-messages",
	provider: "anthropic",
	model: "claude-x",
	usage: { input: 10, output: 2, cacheRead: 0, cacheWrite: 0, totalTokens: 12, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
	stopReason: "stop",
	timestamp: 1730000000000,
};
const next = {
	...baseline,
	content: [
		{ type: "thinking", thinking: "Let me think", thinkingSignature: "sig1" },
		{ type: "text", text: "Hello, world" },
		{ type: "toolCall", id: "call_1", name: "read", arguments: { path: "README.md" } },
	],
	usage: { ...baseline.usage, output: 9, totalTokens: 19 },
	stopReason: "toolUse",
};
const patch = diffCollabStream(baseline, next);
const applied = applyCollabStream(baseline, patch);
log("baseline :", JSON.stringify(baseline));
log("frame    :", JSON.stringify({ t: "stream-delta", id: 1, previousRevision: 0, revision: 1, patch }));
log("result   :", JSON.stringify(applied));
if (!isDeepStrictEqual(applied, next)) throw new Error("applyCollabStream mismatch"); // key order differs; values equal
log("result deep-equals next (key order is not significant):", true);

console.log(out.join("\n"));
