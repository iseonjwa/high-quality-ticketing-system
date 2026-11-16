# syntax=docker/dockerfile:1
# 프론트 SPA를 빌드해 nginx로 서빙한다(ADR 0040). 빌드 인자는 없고 VITE_GRAFANA_URL은 기본값(화면을 연 호스트의 3000번)을 쓴다.
FROM node:24-alpine@sha256:8a32c8cf649a49915a66e81e7a609223c708d0adabf288ee3f4077d765542f85 AS builder
WORKDIR /app
COPY package.json package-lock.json .npmrc ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

FROM nginx:1.30.6-alpine@sha256:0e52bd054126cdc040b1276c0af347aeb02a41b9cfe305d6094767ac30e97949
# deploy.conf는 이미지에 넣지 않는다. 배포 서버에서만 compose.deploy.yml이 default.conf 자리에 마운트한다(ISE-278).
COPY nginx/security-headers.conf nginx/app.conf /etc/nginx/
COPY nginx/gateway-upstream.conf nginx/default.conf /etc/nginx/conf.d/
COPY --from=builder /app/dist /usr/share/nginx/html
