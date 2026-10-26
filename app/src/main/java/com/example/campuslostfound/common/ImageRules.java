package com.example.campuslostfound.common;

/** 与共同接口约定一致；KB 统一按 1024 字节计算。 */
public final class ImageRules {
    public static final int MAX_SIDE = 800;
    public static final int TARGET_BYTES = 300 * 1024;
    public static final int MAX_JPEG_BYTES = 500 * 1024;
    public static final int MAX_BASE64_CHARS = ((MAX_JPEG_BYTES + 2) / 3) * 4;
    public static final String PATH_PREFIX = "/images/";

    private ImageRules() { }

    public static boolean isFileName(String name) {
        return name != null && name.matches("[0-9a-f]{32}\\.jpg");
    }

    public static String imagePath(String name) {
        if (!isFileName(name)) {
            throw new IllegalArgumentException("无效图片文件名");
        }
        return PATH_PREFIX + name;
    }

    public static boolean isImagePath(String path) {
        return path != null && path.startsWith(PATH_PREFIX)
                && isFileName(path.substring(PATH_PREFIX.length()));
    }
}
