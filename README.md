# YSM Mapping API

YSM Mapping API は、Yes Steve Model の class、method、field を構造解析し、型付きの意味 symbol として扱うための profile 駆動基盤です。

`main` は Minecraft／loader に依存しない共有実装だけを管理します。

- `api-core`: mapping target、resolution policy/status、resolved symbol、candidate、汎用 structure constraints
- `analysis-core`: ASM whole-JAR graph、fingerprint、JSON profile/catalog の正規化・検証、profile 駆動解析
- `mapping-tool`: 任意の有効な profile を処理するオフライン CLI

Minecraft 固有の curated registry、request/cache schema、runtime adapter、profile、fixture catalog、Fabric／NeoForge 配布物は `mc/<minecraft-version>` ブランチで管理します。`api` は `api-core` を推移依存として公開し、配布 API JAR に core class を一度だけ埋め込むため、既存利用側の import は変わりません。

## mapping-tool

`mapping-tool` は `api`／`common` に依存せず、`analysis-core` と `api-core` だけを使用します。

```text
graph <ysm-jar> <output-json>
analyze <profile-json> <loader> <ysm-version> <ysm-jar>
equipment-report <profile-json> <loader> <ysm-jar>
registry-report <profile-json> <catalog-json> <official-jar-dir> <output-json>
name-report <profile-json> <candidate-spec-json> <openysm-root> <port-root> <official-jar-dir> <output-json>
```

profile は形式／Minecraft version、loader 型、symbol 定義、構造制約、解析 rule、packet 規則、channel、出典を保持します。正規化した profile SHA-256 は registry definition digest に含まれるため、profile が変わると既存 cache は一度だけ再構築されます。

## 開発

共有ツリーは次で検証します。

```powershell
.\gradlew.bat clean build
```

MC ブランチでは同じ build に加えて `verifyDistributions` を実行します。fixture JAR、runtime name、whole-JAR graph、native library、逆コンパイル結果、private-derived report は Git および配布物へ含めません。
