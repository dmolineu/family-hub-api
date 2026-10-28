import { createServer } from "node:http";
import { query } from "@anthropic-ai/claude-agent-sdk";
import { createGateway } from "./gateway.mjs";

/** A whole number from the environment, or undefined so the gateway's default applies. */
function numberFrom(name) {
  const value = process.env[name];
  if (value === undefined || value.trim() === "") return undefined;
  const number = Number(value);
  if (!Number.isInteger(number) || number <= 0) {
    throw new Error(`${name} must be a positive whole number, not ${JSON.stringify(value)}`);
  }
  return number;
}

const port = numberFrom("PORT") ?? 8080;
const gateway = createGateway({
  // Claude Code signs in with CLAUDE_CODE_OAUTH_TOKEN from this process's environment
  // (from `claude setup-token`); the subprocess inherits it with everything else.
  runQuery: (params) =>
    query({
      ...params,
      options: {
        ...params.options,
        env: { ...process.env, CLAUDE_AGENT_SDK_CLIENT_APP: "family-hub-claude-gateway/1.0.0" },
      },
    }),
  model: process.env.CLAUDE_MODEL || undefined,
  effort: process.env.CLAUDE_EFFORT || undefined,
  maxConcurrent: numberFrom("MAX_CONCURRENT"),
  timeoutMs: numberFrom("TIMEOUT_MS"),
  maxBodyBytes: numberFrom("MAX_BODY_BYTES"),
  credentials: process.env.CLAUDE_CODE_OAUTH_TOKEN ? "token" : "none",
});

const server = createServer(gateway);
server.listen(port, () => {
  const signedIn = process.env.CLAUDE_CODE_OAUTH_TOKEN
    ? "signed in with CLAUDE_CODE_OAUTH_TOKEN"
    : "with no CLAUDE_CODE_OAUTH_TOKEN (in a container, requests will answer NOT_SIGNED_IN)";
  console.log(`Claude gateway listening on ${port}, ${signedIn}`);
});

// docker stop sends SIGTERM: finish what is running, take nothing new.
for (const signal of ["SIGTERM", "SIGINT"]) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
