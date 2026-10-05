# GeyserCheckSkin

Bedrockプレイヤーのログインスキンを検査する **Geyser Extension** です。PvPでプレイヤーを見失う原因になる極小・巨大・欠損モデルや透明な身体を検出し、標準モデル＋任意のPNGへ置換、またはキックします。

## 導入

1. Java 21以上、Geyser **2.11.3系** を用意します。Geyserの内部クラスを利用するため、別のバージョンでは実機検証が必要です。
2. `build/libs/GeyserCheckSkin-1.1.0.jar` を **Geyserの `extensions/`** に配置します。Paperの `plugins/` には配置しません。Geyser-Spigot／Velocity／Standalone等、Geyserが動く側に導入してください。
3. 再起動すると、拡張のデータフォルダー（通常 `extensions/geyser-check-skin/`）に `config.json` と添付画像由来の3枚のPNGを `fallbacks/` に展開します。
4. 設定やPNGを変更したら再起動します。検査は次回ログイン時に実行されます。

初期設定は **小さな装飾のみ許可、不正スキンはフォールバックへ置換** です。

## 設定

配布用初期設定は [`src/main/resources/config.json`](src/main/resources/config.json) にあります。サーバーではデータフォルダーに生成されたファイルを編集してください。

| 項目 | 初期値 | 動作 |
| --- | --- | --- |
| `enabled` | `true` | `false` で検査・置換を停止 |
| `geometryMode` | `MINOR` | 下記の形状ポリシー |
| `violationAction` | `FALLBACK` | `FALLBACK` または `KICK` |
| `allowPersona` | `false` | キャラクタークリエイターのPersonaスキン許可。許可には `ALLOW` と `transparencyCheck: false` も必要 |
| `transparencyCheck` | `true` | 身体のUV面の透明度を検査 |
| `alphaThreshold` | `200` | アルファ値がこの値未満のピクセルは透明扱い（1〜255） |
| `minimumBodyOpacity` | `0.95` | 身体部位ごとに必要な不透明ピクセル割合 |
| `minimumFaceOpacity` | `0.80` | 各面に必要な不透明ピクセル割合 |
| `decorationMargin` | `2.0` | 標準体型の外枠から許可する装飾のはみ出し量（モデル座標。16単位＝1ブロック） |
| `maxDecorationSurfaceArea` | `512.0` | 追加装飾の合計表面積の上限。標準の帽子・ジャケット等は除外 |
| `maxGeometryBytes` | `1048576` | geometry／resource patchそれぞれのUTF-8サイズ上限 |
| `maxBones` / `maxCubes` | `64` / `128` | 選択モデルのボーン・キューブ数の上限 |
| `fallbackFiles` | 3枚のPNGパス配列 | データフォルダー内の64×64 PNG候補。通常のWideモデル用スキン |
| `kickMessage` | 日本語メッセージ | キック時の表示文 |
| `logViolations` | `true` | プレイヤー名・検出理由・処理方法をログへ記録 |

### 形状ポリシー

- **`DENY`**：標準Steve／Alex形状と標準外側レイヤーのみ。組み込みモデルの識別子を偽装しても、geometryが送信された場合は実際の構造を検査します。
- **`MINOR`**：頭・胴・左右の腕・左右の脚、親子構造、基準ピボット、標準UVを維持したモデルに限り、上限内の装飾キューブやボーンを許可します。
- **`ALLOW`**：カスタム形状を許可します。透明度検査は別設定で維持できます。人体の寸法や回転・スケールによる視認性は、このモードでは保証しません。

`MINOR` は安全側の判定です。通常体型に見える場合でも、基準ピボットを変えたモデル、回転装飾、メッシュ、変則UV、継承geometry、標準外の描画フラグは拒否します。任意の4Dスキンを「装飾が少ない」という見た目だけで自動許可するものではありません。

完全禁止＋キックにする場合は以下を変更します。

```json
"geometryMode": "DENY",
"violationAction": "KICK"
```

4D形状を制限せず透明スキンだけ検査する場合は `geometryMode: "ALLOW"` と `transparencyCheck: true` を指定します。標準の身体キューブが確認できるモデルでは身体の面を検査し、それ以外のカスタムモデルでは送信されたキューブのUV面を検査します。後者では透明な装飾も拒否対象になることがあります。

### 透明スキン検査

RGBAのアルファ値を用いて、身体の面ごと・部位ごとの不透明率を検査します。通常スキンの未使用ピクセルや帽子・袖等の外側レイヤーが透明でも違反にはなりません。64×32の旧形式、64×64、128×64、128×128に対応します。顔だけ透明、腕だけ透明等も検査します。

判定はアルファ値とgeometryによるヒューリスティックです。背景と似た色による迷彩、クライアント独自の描画改造、すべてのMarketplaceモデルの合法性は判定できません。Personaは別の描画方式のため標準では拒否し、通常スキンへ置換します。Personaを許可する場合は視認性検査を無効にする必要があります。スキンアニメーションも標準では拒否します。

### 任意のフォールバックスキン

`fallbackFiles` に、拡張のデータフォルダーを基準としたPNGのパスを配列で指定します。初期設定はユーザー提供の以下の3枚です。

```json
"fallbackFiles": [
  "fallbacks/skin1.png",
  "fallbacks/skin2.png",
  "fallbacks/skin3.png"
]
```

違反者のログイン時に候補からランダムで1枚を選びます。同じ接続中は選択を保持し、本人と他のBedrockプレイヤーには同じスキンを表示します。再接続時には再抽選します。1枚だけ指定すると固定スキンになります。配列には1〜128個のパスを指定できます。

候補の追加・変更は、**64×64のWide用PNG** をデータフォルダー内に置き、配列を編集して再起動します。身体の基本面は完全不透明にしてください。外側レイヤーは透明で構いません。元のgeometry、Persona、ケープ、アニメーションを取り除き、選んだPNGと標準Wideモデルを適用します。

起動時に全候補を検証します。空配列、フォルダー外のパス、指定PNGの欠落、不正な画像、身体の透明化、設定値の不備がある場合は起動ログへエラーを出し、新規ログインを拒否します。`enabled: false` の場合は候補を読み込みません。

実装時に確認した公式ソース：

- [GeyserのスキンAPI](https://geysermc.org/wiki/geyser/api/)
- [SkinManagerのキャッシュ・表示処理](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/skin/SkinManager.java)
- [FloodgateSkinUploaderの署名付き元スキン送信](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/skin/FloodgateSkinUploader.java)
- [ログイン後スキンパケットの扱い](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/network/bedrock/CodecProcessor.java)

## ライセンス

本プロジェクトはMIT。標準geometryはGeyserMC/Geyser由来のMITライセンス素材です。