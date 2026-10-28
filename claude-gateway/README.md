# Claude gateway

A small HTTP service that answers structured-output requests with Claude Code, signed in
to a Claude subscription instead of an Anthropic API key. The API's family inbox uses it
to read flyers and recipes (`INBOX_READER_URL`); usage counts toward the subscription's
limits, with no per-request bill.

Each request is one Claude Code run (through the Agent SDK) with **no tools, no MCP
servers and no settings from disk**, and nothing is kept afterwards. What a request
carries, such as an email, can only shape the JSON that comes back.

## Signing it in

On any machine with Claude Code, signed in to the subscription:

```sh
claude setup-token
```

It opens the browser to approve, then prints a token that lasts a year. Give it to the
container as `CLAUDE_CODE_OAUTH_TOKEN`, from a `.env` file that is never committed. Run it
again before the year is out; until then, requests answer `503 NOT_SIGNED_IN`.

## API

`POST /v1/structured`

```json
{
  "system": "Instructions for Claude",
  "content": [
    {"type": "text", "text": "..."},
    {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": "..."}},
    {"type": "document", "source": {"type": "base64", "media_type": "application/pdf", "data": "..."}}
  ],
  "schema": {"type": "object", "...": "a JSON schema for the answer"},
  "model": "claude-opus-5-5",
  "effort": "high"
}
```

`model` and `effort` are optional. Pictures and PDFs are accepted only as data, never by
URL.

| Status | Body | Meaning |
|---|---|---|
| 200 | `{"output": {...}}` | The answer, fitting the schema |
| 200 | `{"refused": true}` | Claude declined to answer |
| 400 | `{"error": "BAD_REQUEST"}` | The request is malformed |
| 413 | `{"error": "TOO_LARGE"}` | Over `MAX_BODY_BYTES` |
| 502 | `{"error": "NO_ANSWER"}` | Claude could not answer it (not worth resending) |
| 429 | `{"error": "BUSY"}` | Already answering `MAX_CONCURRENT` requests; try shortly |
| 503 | `{"error": "NOT_SIGNED_IN"}` | No token, or the token was refused; a person must fix it |
| 503 | `{"error": "UNAVAILABLE"}` | A usage limit, an outage, or Claude Code failed to start; try later |
| 504 | `{"error": "TIMEOUT"}` | No answer within `TIMEOUT_MS` |

Error bodies also carry a `message`. `GET /health` answers
`{"status": "ok", "credentials": "token" | "none"}`.

The service has no authentication of its own: publish no port, and let only the API
reach it on the stack's internal network.

## Settings

| Variable | Default | |
|---|---|---|
| `CLAUDE_CODE_OAUTH_TOKEN` | none | From `claude setup-token` |
| `CLAUDE_MODEL` | `claude-opus-5-5` | When a request names none |
| `CLAUDE_EFFORT` | `high` | When a request names none |
| `MAX_CONCURRENT` | 2 | Runs at once |
| `TIMEOUT_MS` | 240000 | A run's time limit |
| `MAX_BODY_BYTES` | 33554432 | The largest request (32 MB, the Messages API's own limit) |
| `PORT` | 8080 | |

## Development

```sh
npm install
npm test          # scripted Claude Code runs: no sign-in, no network
npm start         # on a Mac, uses the Mac's own Claude Code sign-in
```
