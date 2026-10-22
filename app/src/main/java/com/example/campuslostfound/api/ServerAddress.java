package com.example.campuslostfound.api;

import java.net.URI;
import java.net.URISyntaxException;

/** 基础地址不包含接口路径、账号或查询参数。 */
final class ServerAddress {
    private ServerAddress() { }

    static String normalize(String input) {
        try {
            URI uri = new URI(input == null ? "" : input.trim());
            String path = uri.getRawPath();
            int port = uri.getPort();
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (path != null && !path.isEmpty() && !"/".equals(path))
                    || port < 1 || port > 65535) {
                throw new IllegalArgumentException("请输入完整主机地址，例如 http://192.168.1.20:8080，不要加 /api/ping");
            }
            return "http://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + port;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("主机地址格式不正确，请检查协议、IP 和端口");
        }
    }

    static void checkPath(String path) {
        try {
            URI uri = new URI(path);
            if (!path.startsWith("/api/") || uri.isAbsolute() || uri.getRawAuthority() != null
                    || uri.getRawFragment() != null || path.contains("\\")
                    || !uri.normalize().equals(uri)) {
                throw new IllegalArgumentException("接口路径必须以 /api/ 开头，且不能包含主机地址或上级路径");
            }
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalArgumentException("接口路径格式不正确");
        }
    }
}
