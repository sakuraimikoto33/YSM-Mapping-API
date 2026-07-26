# YSM Mapping API

YSM Mapping APIは、Yes Steve Model（YSM）のclass、method、fieldを構造解析し、型付きsymbolとして扱うための基盤です。

`main`ではMinecraftやloaderに依存しない共有実装を管理します。

- `api-core`: 公開型と解決結果
- `analysis-core`: bytecodeとprofileの解析
- `mapping-tool`: ローカル検証用CLI

Minecraft固有のprofile、runtime、loader統合は`mc/<minecraft-version>`ブランチで管理します。

## 開発

```powershell
.\gradlew.bat clean build
```

YSMのJARや解析結果などの私有データはGitや配布物へ含めません。
