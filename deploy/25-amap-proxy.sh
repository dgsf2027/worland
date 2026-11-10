#!/bin/sh
set -eu
mkdir -p /etc/nginx/amap
if [ -z "${AMAP_SECURITY_JS_CODE:-}" ]; then
    printf '%s\n' 'location ^~ /_AMapService/ { return 503; }' > /etc/nginx/amap/locations.conf
else
    # 安全密钥只接受平台使用的字母数字，防止环境变量成为 nginx 配置代码。
    case "$AMAP_SECURITY_JS_CODE" in *[!a-zA-Z0-9]*) echo 'Invalid AMAP_SECURITY_JS_CODE format' >&2; exit 1;; esac
    envsubst '${AMAP_SECURITY_JS_CODE}' < /opt/amap-proxy.conf.template > /etc/nginx/amap/locations.conf
fi
