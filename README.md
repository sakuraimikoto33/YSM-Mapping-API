# YSM Mapping API

YSM Mapping APIは、導入済みYes Steve Modelの難読化されたclass、method、fieldを構造解析し、他modへ型付きの意味キーとして提供するMinecraft 1.21.1向け前提modです。

- Fabric／NeoForge、Java 21、YSM 2.6.0以上
- 実行時のネット照会、YSMの自動ダウンロード、対応版上限なし
- version-specific baselineを同梱せず、すべてのYSM版を同じ`STRUCTURAL`経路で解析
- 公開registryはsemantic key 94件（Serverless 62件、Equipment直接9件、関連23件）
- registry外はconsumer所有の構造definitionとsource aliasで要求
- 既定は`SAFE_ONLY`。一意に検証できない候補は返さない
- cacheは固定ファイル名で、最後に正常に解析したYSMだけを保持

## 利用modからの要求

利用modは自身のJARへ`META-INF/ysm-mapping-api/requests-v1.json`を格納します。consumer mod IDはJSONではなく、このresourceを所有するmod containerから取得されます。

```json
{
  "schemaVersion": 1,
  "symbols": [
    {
      "key": "ysm.network.packet.1.class",
      "kind": "CLASS",
      "required": true,
      "sourceAlias": {
        "common": { "owner": "net/okitsu/example/ysmref/PacketOne" },
        "fabric": {},
        "neoforge": {}
      }
    },
    { "key": "ysm.client.model_manager.start_sync.method", "kind": "METHOD", "required": true },
    { "key": "ysm.player_state.flags.field", "kind": "FIELD", "required": false }
  ],
  "mixinRequirements": {
    "net.example.mixin.YsmPacketMixin": [
      "ysm.network.packet.1.class"
    ]
  }
}
```

通常初期化後は`YsmMappingApi.resolve(consumerModId, symbolKeys)`を呼び、`MappingSnapshot`から`YsmClassSymbol`、`YsmMethodSymbol`、`YsmFieldSymbol`を取得します。`YsmSymbols.registry()`が読み取り専用の94件のsemantic registryを公開します。`ysm.*`の未登録keyは拒否され、追加用途は`consumerClass`／`consumerMethod`／`consumerField`でmod IDごとにscopeします。

Mixinを将来版YSMへremapするconfigではpluginとruntime refmap wrapperを指定します。wrapperは利用mod側のMixin packageに置き、`YsmMappingReferenceMapper`を継承します。Mixinから参照するsymbolにはconsumer所有の`sourceAlias`が必須です。`common`を完全なaliasとし、`fabric`／`neoforge`は指定フィールドだけを上書きします。Reflection／MethodHandleだけで使用するcurated keyにはaliasは不要です。

```json
{
  "package": "net.example.mixin",
  "plugin": "net.okitsu.ysmmapping.internal.mixin.YsmMappingMixinPlugin",
  "refmapWrapper": "YsmReferenceMapper"
}
```

## 保存ファイル

```text
config/ysm_mapping_api/
├─ settings.json
├─ mappings.json
└─ mappings.lock  # mapping処理中だけ作成
```

`mappings.lock`は排他処理の終了後に削除され、未使用状態では残りません。`mappings.json`にはMC版、loader、YSM版、YSM class内容の完全SHA-512を`target`として保存します。パスへversionやhashは含めません。

```json
{
  "schemaVersion": 1,
  "fingerprintAlgorithm": 1,
  "registryDefinitionSha256": "<64 lowercase hex characters>",
  "fingerprintDefinitionSha256": "<64 lowercase hex characters>",
  "resolutionPolicy": "SAFE_ONLY",
  "target": {
    "minecraftVersion": "1.21.1",
    "loader": "fabric",
    "ysmVersion": "2.6.5-fabric+mc1.21.1",
    "contentSha512": "<128 lowercase hex characters>"
  },
  "entries": {
    "ysm.client.model_manager.start_sync.method": {
      "origin": "CURATED",
      "definitionRevision": 1,
      "definitionSha256": "<64 lowercase hex characters>",
      "kind": "METHOD",
      "status": "STRUCTURAL",
      "confidence": 1.0,
      "resolved": {
        "owner": "com/elfmcys/yesstevemodel/<obfuscated-owner>",
        "name": "<obfuscated-method>",
        "descriptor": "(Lnet/minecraft/class_2535;Ljava/nio/ByteBuffer;)V"
      },
      "candidates": []
    }
  },
  "consumers": {
    "example_mod": {
      "manifestSchemaVersion": 1,
      "requests": {
        "ysm.client.model_manager.start_sync.method": {
          "definitionRevision": 1,
          "kind": "METHOD",
          "definitionSha256": "<64 lowercase hex characters>",
          "sourceAliasSha256": null,
          "required": true
        }
      }
    }
  }
}
```

同じtarget、registry digest、fingerprint digest、要求definition digestで全要求が揃っていれば無書込みで読み込みます。同じYSMへ要求が増えた場合は既存consumerを残して不足entryだけを追加します。consumer definitionだけが変わればそのscoped entryだけを再解析します。alias digestはdefinition digestから独立しているため、aliasだけの変更ではYSM構造を再解析せずconsumer metadataとremapperを更新します。YSMやglobal digestが変わった場合は全要求を再収集して単一targetへatomic replaceし、旧target、履歴、backup、短縮hashディレクトリは作りません。

OpenYSM由来の実名は`CuratedDefinitionRegistry`のprovenanceと構造定義の検証にだけ使用し、公開IDやversion-specific runtime対応は同梱しません。whole-JAR graphの匿名nodeは実行中だけ`@anon/sha256/<64-hex>`で識別されます。

## 開発

```powershell
.\gradlew.bat clean build verifyDistributions
```

`mapping-tool`は開発時のローカルJAR検証専用です。公式2.6.0～2.6.5のloader別12 JARはGit管理外のfixtureとして`registry-report`で検証し、結果だけを`build/reports`へ出力します。YSM JAR、runtime名、whole-JAR graph、native library、逆コンパイル結果はGitおよび配布物へ含めません。
