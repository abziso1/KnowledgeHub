FROM node:24.13.0-bookworm-slim AS build
WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-fund
COPY frontend/index.html frontend/tsconfig.json frontend/vite.config.ts ./
COPY frontend/src src
RUN npm run build

FROM nginx:1.31.9-alpine
COPY --from=build /workspace/dist /usr/share/nginx/html
COPY ops/nginx/default.conf.template /etc/nginx/templates/http/default.conf.template
COPY ops/nginx/tls.conf.template /etc/nginx/templates/https/default.conf.template
