# 沃朗租赁板块 前端生产镜像(node 构建 + nginx 静态托管)
FROM node:22-alpine AS build
WORKDIR /build
# 国内镜像源 + pnpm@9(pnpm10+ 的 build 脚本审批门 ERR_PNPM_IGNORED_BUILDS 会拦 esbuild/vue-demi;
# pnpm9 无此门,esbuild postinstall 自动跑装平台二进制。lockfile v9 格式两者兼容)
RUN npm config set registry https://registry.npmmirror.com && npm i -g pnpm@9
COPY frontend/package.json frontend/pnpm-lock.yaml* ./
RUN pnpm install --no-frozen-lockfile
COPY frontend/ .
RUN pnpm build

FROM nginx:alpine
COPY deploy/frontend-nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
