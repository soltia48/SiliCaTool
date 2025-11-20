# SiliCaTool

FeliCa を読み取り、選んだデータを SiliCa に書き込む Android NFC ユーティリティ。

## 概要

SiliCaTool は Jetpack Compose 製の Android アプリです。FeliCa を読み取り、最大 4 つのシステムとサービスを独立して選択し、ブロック内容を確認して SiliCa に書き込みます。サービスのブロックは 12 ブロックにトリムまたは 0 埋めし、IDm/PMm・システムコード・サービスコードと共に保存します。

## 特徴

- システムを選択していなくても、システムごとにサービスを一覧表示
- 最大 4 システム・4 サービスを選択し、書き込み元サービスを 1 つ指定
- Shift_JIS デコード付きのブロック内容表示（HEX/テキスト）
- 12 ブロック制限に合わせてトリムまたは 0 埋めし、IDm/PMm を保持して SiliCa に書き込み
- ステップガイド付きフロー（読取→選択→確認→書込）とエラーメッセージ

## 必要環境

### ハードウェア
- NFC-F (FeliCa) 対応 Android デバイス
- 読み取り用 FeliCa カード
- 書き込み用 SiliCa タグ

### ソフトウェア
- Android Studio（Hedgehog 以降）
- Android SDK 36（build-tools 含む）
- JDK 11 以上

## ビルドとインストール

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

または Android Studio で `app` モジュールを NFC 対応デバイスに実行します。

## 使い方

1. アプリを起動し「FeliCa を読み取る」を押してカードをかざす。
2. 任意でシステムを選び、書き込みたいサービスを選択（書き込み元サービスを 1 つ指定）。
3. サマリーで内容を確認。12 ブロック超のサービスは先頭 12 にトリム、足りない場合は 0 埋め。
4. 「SiliCa に書き込む」を押し、SiliCa タグをかざして IDm/PMm・システムコード・サービスコード・ブロックを書き込む。

## プロジェクト構成

- `app/src/main/java/ws/nyaa/silicatool/MainActivity.kt`: UI フローと選択ロジック
- `app/src/main/java/ws/nyaa/silicatool/FelicaClient.kt`: FeliCa の読み書き処理とフレーム生成
- `app/src/main/java/ws/nyaa/silicatool/FelicaModels.kt`: データモデルとヘルパー

## ライセンス

[MIT](https://opensource.org/licenses/MIT)

Copyright (c) 2025 KIRISHIKI Yudai
