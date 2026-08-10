# 沃朗租赁板块 前端生产镜像(node 构建 + nginx 静态托管)
FROM node:22-alpine AS build
WORKDIR /build
# 国内镜像源 + pnpm(ECS 直连 npmjs 会超时)
RUN npm config set registry https://registry.npmmirror.com && npm i -g pnpm@11
COPY frontend/package.json frontend/pnpm-lock.yaml* frontend/pnpm-workspace.yaml* ./
RUN pnpm install --frozen-lockfile || pnpm install
COPY frontend/ .
RUN pnpm build

FROM nginx:alpine
COPY deploy/frontend-nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
