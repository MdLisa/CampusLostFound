package com.example.campuslostfound.server;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.util.Base64;

import com.example.campuslostfound.common.ApiException;
import com.example.campuslostfound.common.ImageCompressor;
import com.example.campuslostfound.common.ImageRules;
import com.example.campuslostfound.data.AppDbHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

import fi.iki.elonen.NanoHTTPD;

/** 主机私有 JPEG 目录。图片解码、写文件均在数据库锁外完成。 */
public final class ImageStore {
    private final File directory;
    private final AppDbHelper helper;

    public ImageStore(Context context, AppDbHelper helper) throws IOException {
        this.helper = helper;
        directory = new File(context.getApplicationContext().getFilesDir(), "images").getCanonicalFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建图片目录");
        }
    }

    public String saveJpeg(String base64) throws IOException {
        if (base64 == null || base64.isEmpty()) throw invalid("图片内容不能为空");
        if (base64.length() > ImageRules.MAX_BASE64_CHARS) {
            throw new ApiException(NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE, "图片不能超过 500 KB");
        }
        if (base64.length() % 4 != 0 || !base64.matches("[A-Za-z0-9+/]+={0,2}")) {
            throw invalid("图片 Base64 格式错误");
        }
        byte[] jpeg;
        try {
            jpeg = Base64.decode(base64, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            throw invalid("图片 Base64 格式错误");
        }
        if (jpeg.length > ImageRules.MAX_JPEG_BYTES) {
            throw new ApiException(NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE, "图片不能超过 500 KB");
        }
        try {
            Bitmap preview = ImageCompressor.decodeJpeg(jpeg);
            preview.recycle();
        } catch (IOException e) {
            throw invalid("图片必须为可解码的完整 JPEG");
        }
        String name = UUID.randomUUID().toString().replace("-", "") + ".jpg";
        File destination = resolve(name);
        File temporary = File.createTempFile("upload_", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(jpeg);
                output.getFD().sync();
            }
            if (destination.exists() || !temporary.renameTo(destination)) {
                throw new IOException("无法保存图片");
            }
            return name;
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                android.util.Log.w("ImageStore", "临时图片清理失败");
            }
        }
    }

    public String imagePath(String name) {
        return ImageRules.imagePath(name);
    }

    public void deleteJpeg(String name) {
        try {
            File file = resolve(name);
            if (file.exists() && !file.delete()) throw new IllegalStateException("无法清理本次图片");
        } catch (IOException e) {
            throw new IllegalStateException("无法清理本次图片", e);
        }
    }

    public FileInputStream openPublishedJpeg(String name) throws IOException {
        return openJpeg(name, false);
    }

    /** 仅主机本地审查历史内容使用，不由网络路由调用。 */
    public FileInputStream openAdminJpeg(String name) throws IOException {
        return openJpeg(name, true);
    }

    private FileInputStream openJpeg(String name, boolean includeDeleted) throws IOException {
        if (!ImageRules.isFileName(name)) throw notFound();
        boolean published = helper.read(database -> {
            try (Cursor cursor = database.rawQuery(
                    "SELECT 1 FROM posts WHERE image_name=?" + (includeDeleted ? "" : " AND is_deleted=0") + " LIMIT 1", new String[]{name})) {
                return cursor.moveToFirst();
            }
        });
        if (!published) throw notFound();
        File file = resolve(name);
        if (!file.isFile() || file.length() > ImageRules.MAX_JPEG_BYTES) throw notFound();
        try {
            return new FileInputStream(file);
        } catch (FileNotFoundException e) {
            throw notFound();
        }
    }

    private File resolve(String name) throws IOException {
        if (!ImageRules.isFileName(name)) throw notFound();
        File file = new File(directory, name).getCanonicalFile();
        if (!directory.equals(file.getParentFile())) throw notFound();
        return file;
    }

    private ApiException invalid(String message) {
        return new ApiException(NanoHTTPD.Response.Status.BAD_REQUEST, message);
    }

    private ApiException notFound() {
        return new ApiException(NanoHTTPD.Response.Status.NOT_FOUND, "图片不存在");
    }
}
