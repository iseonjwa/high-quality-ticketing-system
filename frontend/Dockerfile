# syntax=docker/dockerfile:1
# 프론트 SPA를 빌드해 nginx로 서빙한다(ADR 0040). 빌드 인자는 없고 VITE_GRAFANA_URL은 기본값을 쓴다.
FROM node:24-alpine@sha256:8a32c8cf649a49915a66e81e7a609223c708d0adabf288ee3f4077d765542f85 AS builder
WORKDIR /app
COPY package.json package-lock.json .npmrc ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

FROM nginx:1.30.6-alpine@sha256:0e52bd054126cdc040b1276c0af347aeb02a41b9cfe305d6094767ac30e97949
COPY nginx/security-headers.conf /etc/nginx/security-headers.conf
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
