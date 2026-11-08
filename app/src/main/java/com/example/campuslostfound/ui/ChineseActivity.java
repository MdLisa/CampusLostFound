package com.example.campuslostfound.ui;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/** 所有页面统一使用简体中文，包括框架提供的菜单和对话框文字。 */
public abstract class ChineseActivity extends AppCompatActivity {
    private static final LocaleListCompat CHINESE = LocaleListCompat.forLanguageTags("zh-CN");

    @Override
    protected void attachBaseContext(Context base) {
        // 旧系统在创建页面之前提供语言，进程恢复后也能使用中文资源。
        if (Build.VERSION.SDK_INT < 33) {
            AppCompatDelegate.setApplicationLocales(CHINESE);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // 新系统由应用语言设置持久保存；只在首次或语言被改变时更新。
        if (Build.VERSION.SDK_INT >= 33
                && !CHINESE.equals(AppCompatDelegate.getApplicationLocales())) {
            AppCompatDelegate.setApplicationLocales(CHINESE);
        }
    }
}
