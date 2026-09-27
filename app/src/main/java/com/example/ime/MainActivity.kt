package com.example.ime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.example.ime.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 実際のパッケージインストール更新日時を表示
        val installTimeStr = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN).format(Date(pInfo.lastUpdateTime))
        } catch (e: Exception) {
            "Unknown"
        }

        binding.tvBuildInfo.text = "Installed: $installTimeStr | 41k Vocab (Fast)"

        // 1. IMEを有効化する設定画面へ
        binding.btnEnable.setOnClickListener {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        // 2. IME切り替えダイアログを表示
        binding.btnSelect.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }
}
