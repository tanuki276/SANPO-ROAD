# サンポロード

Kotlin + Jetpack Composeで作った散歩記録アプリのMVPです。

## 実装済み
- Google Maps表示
- GPSで現在地取得
- 「記録開始」→位置情報を定期取得し、歩いたルートをPolylineで表示
- 「停止」→距離/所要時間を集計
- コース名を付けて端末へ保存
- 保存済みコース一覧から選択して地図へ復元
- 任意のローカルログイン画面（ログイン不要でゲスト利用も可能）
- シンプルな日本語UI

## Google Maps APIキー
`local.properties` に以下を追加してください。

MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY

Maps SDK for Android を有効化したGoogle Maps Platform APIキーが必要です。

## ビルド
Android Studioでこのフォルダを開き、Gradle Sync後に `app` のdebug APKをビルドしてください。

`app/build/outputs/apk/debug/app-debug.apk`

この環境ではAndroid SDK/Gradle本体が利用できないため、ここからAPKバイナリを生成できませんでした。
