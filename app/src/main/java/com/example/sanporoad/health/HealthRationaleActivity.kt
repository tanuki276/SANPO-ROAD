package com.example.sanporoad
import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
class HealthRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ScrollView(this).apply { addView(TextView(this@HealthRationaleActivity).apply {
            setPadding(32,32,32,32)
            text="Health Connectとの連携\n\n許可した場合、SANPOの散歩をウォーキングと距離として書き込みます。歩数、心拍、消費カロリーは散歩の時間帯の集計を読み取り、端末の画面に表示します。健康データをクラウド同期・共有・JSONバックアップには含めません。インポート記録は送信しません。権限はHealth Connectの設定からいつでも取り消せます。SANPOの記録削除ではHealth Connectのデータは削除されません。Health Connect側で管理できます。\n\n" + assets.open("privacy.md").bufferedReader().use { it.readText() }
        }) })
    }
}
