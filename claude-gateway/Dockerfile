# The Claude gateway: Claude Code (through the Agent SDK, which bundles its native
# build for this platform) behind a small HTTP API. Signed in with a Claude
# subscription's token in CLAUDE_CODE_OAUTH_TOKEN, given at run time, never baked in.
FROM node:24-slim

ENV NODE_ENV=production \
    # A server that only answers requests needs no auto-updates, telemetry or error
    # reports; versions change by rebuilding this image.
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
    DISABLE_AUTOUPDATER=1 \
    PORT=8080

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY gateway.mjs server.mjs ./

# Claude Code keeps its own small config under $HOME; the node user's home holds it.
USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD ["node", "-e", "fetch('http://127.0.0.1:8080/health').then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"]
CMD ["node", "server.mjs"]
