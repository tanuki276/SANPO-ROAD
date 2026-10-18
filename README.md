# サンポロード

Kotlin + Jetpack Compose で作った Android 向けの散歩記録アプリです（Android 8.0 以上）。

## できること

- OpenStreetMap の地図に GPS で歩いた道を記録
- 距離・時間・ペース・GPS 精度を表示、現在地への追従を切り替え
- バックグラウンド記録と、通知からアプリを開く／散歩を終了する操作
- 名前・メモ・写真を添えて端末に保存、クラウド同期を再試行
- 記録を名前・メモで検索し、新しい順／距離順／時間順に表示
- 週間目標、月間カレンダー、過去の散歩との比較、共有
- 下部ナビゲーションと端末設定に合わせたライト／ダークテーマ

## 使い方

1. 「散歩」で「散歩を開始」を押し、正確な位置情報を許可します。端末の位置情報もオンにしてください。
2. GPS 取得後、歩いた道が地図に表示されます。画面を閉じても通知から記録に戻れます。
3. 「散歩を終了」後、名前や写真を添えて保存します。GPS が取得できなかった記録は保存できません。
4. 「記録」から検索・並び替え・地図表示、「振り返り」から目標や統計を確認できます。

通知を許可しなくても記録は可能です。記録の破棄には確認が必要です。保存や写真のコピーに失敗した場合は記録を保持し、写真を外すなどして再試行できます。同期できなくても保存済みの記録は端末に残ります。右上の再同期ボタンで再試行してください。

## ビルドとテスト

- JDK 21、Gradle 8.9、Android SDK Platform 35 / Build Tools 35.0.0
- Android Studio でプロジェクトを開き、SDK パスを `local.properties` に設定
- 現在の地図は osmdroid / OpenStreetMap を利用するため、Google Maps API キーは不要
- このリポジトリには `gradle-wrapper.jar` がないため、インストール済みの Gradle または GitHub Actions を使用

```sh
gradle --no-daemon testDebugUnitTest assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions は PR と main への push 時に単体テストと APK ビルドを実行します。実機で確認する手順は [docs/QA.md](docs/QA.md) を参照してください。
