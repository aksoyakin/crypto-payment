# syntax=docker/dockerfile:1.7
# docker build -f infra/docker/web.Dockerfile .

FROM node:24-alpine AS build
RUN corepack enable
WORKDIR /src
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY web/package.json web/
RUN --mount=type=cache,id=pnpm,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
COPY web web
ENV NEXT_TELEMETRY_DISABLED=1
RUN pnpm --filter @cryptopayment/web build

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=app:app /src/web/.next/standalone ./
COPY --from=build --chown=app:app /src/web/.next/static ./web/.next/static
COPY --from=build --chown=app:app /src/web/public ./web/public
USER app
EXPOSE 3000
CMD ["node", "web/server.js"]
