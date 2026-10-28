/**
 * The gateway's HTTP handler: one request becomes one Claude Code run that answers
 * through a JSON schema. Claude Code runs with no tools, no MCP servers and no settings
 * from disk, so what a request carries (an email, say) can only ever shape the JSON
 * that comes back; it has nothing to act on.
 *
 * POST /v1/structured  {system, content: [text|image|document blocks], schema, model?, effort?}
 *   200 {output}              the structured output
 *   200 {refused: true}       Claude declined to answer
 *   400 BAD_REQUEST, 413 TOO_LARGE, 502 NO_ANSWER     not worth sending again
 *   429 BUSY, 503 NOT_SIGNED_IN | UNAVAILABLE, 504 TIMEOUT   worth trying later
 * GET /health  {status: "ok", credentials: "token" | "none"}
 */

const EFFORTS = new Set(["low", "medium", "high", "xhigh", "max"]);
const IMAGE_TYPES = new Set(["image/jpeg", "image/png", "image/gif", "image/webp"]);
const MAX_BLOCKS = 100;
/** Assistant-message errors that need a person: sign in again or fix the account. */
const ACCOUNT_ERRORS = new Set([
  "authentication_failed",
  "oauth_org_not_allowed",
  "account_on_hold",
  "verification_required",
  "billing_error",
]);
/** Assistant-message errors that pass by themselves. */
const PASSING_ERRORS = new Set(["rate_limit", "overloaded", "server_error"]);

/**
 * @param {object} options
 * @param {(params: {prompt: AsyncIterable<object>, options: object}) => AsyncIterable<object>} options.runQuery
 *   the Agent SDK's `query`, or a stand-in in tests
 * @param {string} [options.model] the model when a request names none
 * @param {string} [options.effort] the effort when a request names none
 * @param {number} [options.maxConcurrent] runs at once; more are answered BUSY
 * @param {number} [options.timeoutMs] a run's time limit
 * @param {number} [options.maxBodyBytes] the largest request accepted
 * @param {"token" | "none"} [options.credentials] what /health reports
 * @param {(line: string) => void} [options.log]
 * @returns {(request: import("node:http").IncomingMessage, response: import("node:http").ServerResponse) => Promise<void>}
 */
export function createGateway({
  runQuery,
  model = "claude-opus-5-5",
  effort = "high",
  maxConcurrent = 2,
  timeoutMs = 240_000,
  maxBodyBytes = 32 * 1024 * 1024,
  credentials = "none",
  log = console.log,
}) {
  let running = 0;

  return async (request, response) => {
    const path = new URL(request.url ?? "/", "http://gateway").pathname;
    if (path === "/health") {
      if (request.method !== "GET") return refuseMethod(response, "GET");
      return send(response, 200, { status: "ok", credentials });
    }
    if (path !== "/v1/structured") {
      request.resume();
      return send(response, 404, { error: "NOT_FOUND", message: `No ${path} here` });
    }
    if (request.method !== "POST") return refuseMethod(response, "POST");

    const started = Date.now();
    const finish = (status, body, note = "") => {
      send(response, status, body);
      // The outcome only: never the request, which carries the family's mail.
      log(`structured ${status}${body.error ? ` ${body.error}` : ""} in ${Date.now() - started} ms${note}`);
    };

    if (running >= maxConcurrent) {
      request.resume();
      return finish(429, { error: "BUSY", message: `Already answering ${running}; try again shortly` });
    }
    running++;
    try {
      const raw = await readBody(request, maxBodyBytes);
      if (raw === null) {
        return finish(413, { error: "TOO_LARGE", message: `Requests are limited to ${maxBodyBytes} bytes` });
      }
      let body;
      try {
        body = JSON.parse(raw);
      } catch {
        return finish(400, { error: "BAD_REQUEST", message: "The request is not JSON" });
      }
      const checked = check(body);
      if (typeof checked === "string") {
        return finish(400, { error: "BAD_REQUEST", message: checked });
      }

      const runModel = checked.model ?? model;
      const controller = new AbortController();
      // A caller that gives up (its own time limit) stops the run too.
      response.on("close", () => {
        if (!response.writableEnded) controller.abort();
      });
      const run = await answer(runQuery, checked, { model: runModel, effort: checked.effort ?? effort, controller, timeoutMs });
      const cost = run.cost === undefined ? "" : `, about $${run.cost.toFixed(3)} of usage`;
      return finish(run.status, run.body, `, ${runModel}${cost}`);
    } finally {
      running--;
    }
  };
}

/** Runs Claude Code once and turns what it reports into the gateway's answer. */
async function answer(runQuery, request, { model, effort, controller, timeoutMs }) {
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let result = null;
  let refused = false;
  let assistantError = null;
  let thrown = null;
  try {
    const messages = runQuery({
      prompt: once({
        type: "user",
        parent_tool_use_id: null,
        message: { role: "user", content: request.content },
      }),
      options: {
        systemPrompt: request.system,
        tools: [],
        settingSources: [],
        strictMcpConfig: true,
        persistSession: false,
        model,
        effort,
        outputFormat: { type: "json_schema", schema: request.schema },
        abortController: controller,
      },
    });
    for await (const message of messages) {
      if (message.type === "assistant" && message.error) assistantError = message.error;
      if (message.type === "system" && message.subtype === "model_refusal_no_fallback") refused = true;
      if (message.type === "result") result = message;
    }
  } catch (error) {
    // The SDK throws after an error result; the result says more than the throw.
    thrown = error;
  } finally {
    clearTimeout(timer);
  }

  const cost = typeof result?.total_cost_usd === "number" ? result.total_cost_usd : undefined;
  if (controller.signal.aborted) {
    return { status: 504, body: { error: "TIMEOUT", message: "No answer in time" }, cost };
  }
  if (result && !result.is_error && result.subtype === "success") {
    if (result.structured_output !== undefined && result.structured_output !== null) {
      return { status: 200, body: { output: result.structured_output }, cost };
    }
    if (refused || result.stop_reason === "refusal") return { status: 200, body: { refused: true }, cost };
    return { status: 502, body: { error: "NO_ANSWER", message: "Claude finished without the structured output" }, cost };
  }
  if (refused) return { status: 200, body: { refused: true }, cost };

  const text = describe(result, thrown);
  const apiStatus = result?.api_error_status ?? null;
  if (ACCOUNT_ERRORS.has(assistantError) || apiStatus === 401 || apiStatus === 403
      || /not logged in|authenticat|oauth/i.test(text)) {
    return { status: 503, body: { error: "NOT_SIGNED_IN", message: text }, cost };
  }
  if (!result || PASSING_ERRORS.has(assistantError) || apiStatus === 429 || (apiStatus !== null && apiStatus >= 500)
      || /rate limit|usage limit|overloaded/i.test(text)) {
    // No result at all means Claude Code itself did not run properly: worth retrying.
    return { status: 503, body: { error: "UNAVAILABLE", message: text }, cost };
  }
  return { status: 502, body: { error: "NO_ANSWER", message: text }, cost };
}

function describe(result, thrown) {
  const text = [result?.result, ...(result?.errors ?? [])].filter(Boolean).join("; ")
    || result?.subtype
    || thrown?.message
    || "Claude Code stopped without an answer";
  return String(text).slice(0, 300);
}

/** The request, rebuilt from only the fields it may carry; or why it is refused. */
function check(body) {
  if (body === null || typeof body !== "object" || Array.isArray(body)) return "The request must be a JSON object";
  if (typeof body.system !== "string" || body.system.trim() === "") return "system must be a non-empty string";
  if (!Array.isArray(body.content) || body.content.length === 0 || body.content.length > MAX_BLOCKS) {
    return `content must hold 1 to ${MAX_BLOCKS} blocks`;
  }
  const content = [];
  for (const block of body.content) {
    const rebuilt = checkBlock(block);
    if (typeof rebuilt === "string") return rebuilt;
    content.push(rebuilt);
  }
  const { schema } = body;
  if (schema === null || typeof schema !== "object" || Array.isArray(schema) || schema.type !== "object") {
    return "schema must be a JSON schema of an object";
  }
  if (body.model !== undefined && (typeof body.model !== "string" || body.model.trim() === "")) {
    return "model must be a model's name";
  }
  if (body.effort !== undefined && !EFFORTS.has(body.effort)) {
    return `effort must be one of ${[...EFFORTS].join(", ")}`;
  }
  return { system: body.system, content, schema, model: body.model, effort: body.effort };
}

function checkBlock(block) {
  if (block === null || typeof block !== "object") return "Every content block must be an object";
  if (block.type === "text") {
    return typeof block.text === "string" ? { type: "text", text: block.text } : "A text block needs its text";
  }
  // Pictures and PDFs only as data in the request: a URL source would have Anthropic
  // fetch whatever address it names.
  const source = block.source;
  const base64 = source && source.type === "base64" && typeof source.data === "string";
  if (block.type === "image") {
    if (!base64 || !IMAGE_TYPES.has(source.media_type)) {
      return `An image must be base64 data of type ${[...IMAGE_TYPES].join(", ")}`;
    }
    return { type: "image", source: { type: "base64", media_type: source.media_type, data: source.data } };
  }
  if (block.type === "document") {
    if (!base64 || source.media_type !== "application/pdf") return "A document must be base64 PDF data";
    return { type: "document", source: { type: "base64", media_type: "application/pdf", data: source.data } };
  }
  return `Blocks of type ${JSON.stringify(block.type)} are not accepted`;
}

async function* once(message) {
  yield message;
}

/** The body as text, or null when it is larger than allowed (it is still drained). */
function readBody(request, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let tooLarge = Number(request.headers["content-length"] ?? 0) > maxBytes;
    request.on("data", (chunk) => {
      size += chunk.length;
      if (size > maxBytes) tooLarge = true;
      if (!tooLarge) chunks.push(chunk);
    });
    request.on("end", () => resolve(tooLarge ? null : Buffer.concat(chunks).toString("utf8")));
    request.on("error", reject);
  });
}

function refuseMethod(response, allowed) {
  response.setHeader("Allow", allowed);
  return send(response, 405, { error: "METHOD_NOT_ALLOWED", message: `Use ${allowed}` });
}

function send(response, status, body) {
  if (response.headersSent) return;
  const text = JSON.stringify(body);
  response.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(text) });
  response.end(text);
}
